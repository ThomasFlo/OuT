"""Wine enrichment via a local Ollama server.

We POST a strict-JSON sommelier prompt to the Ollama HTTP API; the
``format: "json"`` parameter constrains the model to emit a JSON object.
A small instruct model (3B) is enough — the schema is tight and the call
is one-shot.

If ``OLLAMA_BASE_URL`` is empty, the endpoint that calls into here raises
EnrichmentDisabled, which maps to HTTP 503 — so the rest of the app keeps
working without a local LLM.
"""
from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass

import httpx

log = logging.getLogger("homestock.wine_enrichment")

# Defaults match docker-compose. Override via env to point at a remote
# Ollama or swap the model (e.g. mistral:7b-instruct on a 12+ GB NAS).
DEFAULT_BASE_URL = "http://homestock-ollama:11434"
DEFAULT_MODEL = "llama3.2:3b"

# Generous timeout for the cold-start case (model not yet in RAM). The 3B
# model loads in ~5-10 s and a warm call returns in 3-5 s; 90 s is enough
# headroom for both load and a long generation.
CALL_TIMEOUT_SECONDS = 90.0

SYSTEM_PROMPT = """Tu es un sommelier expert français. À partir des \
informations de base sur un vin (appellation, domaine, millésime, type), \
tu retournes UNIQUEMENT un objet JSON valide (pas de texte autour, pas \
de markdown) avec ces clés :

{
  "summary": "Résumé sommelier en 2-4 phrases en français — caractère, robe, \
arômes typiques.",
  "apogee_year_min": <année entière où le vin entre dans sa fenêtre optimale>,
  "apogee_year_max": <année entière où il sort de cette fenêtre>,
  "keeping_year_max": <année limite au-delà de laquelle il ne se conserve plus>,
  "pairings_ideal": ["plat 1", "plat 2", "plat 3"],
  "pairings_possible": ["plat 4", "plat 5", "plat 6", "plat 7"]
}

==============================================================================
GUIDE D'ESTIMATION DES FENÊTRES DE GARDE
==============================================================================

PRINCIPE FONDAMENTAL
--------------------
1. Note M = le millésime (l'année figurant sur l'étiquette).
2. Calcule les trois années en ajoutant un offset à M :
       apogee_year_min  = M + offset_debut
       apogee_year_max  = M + offset_apogee_fin
       keeping_year_max = M + offset_garde_max
3. N'utilise JAMAIS l'année courante comme point d'ancrage. Un vin millésime
   2010 ne peut PAS entrer en apogée en 2030 « parce que c'est maintenant ».
4. Si le millésime est inconnu, prends M = 2024 (millésime « actuel »
   théorique) — n'imagine pas un autre millésime au hasard.
5. Toujours respecter : apogee_year_min ≤ apogee_year_max ≤ keeping_year_max.
6. Si tu ne reconnais ni l'appellation ni le type → null/[] partout, et
   summary = "Informations insuffisantes pour un avis fiable."

ALGORITHME DE DÉCISION (à exécuter mentalement avant de produire le JSON)
------------------------------------------------------------------------
Étape 1 : identifie la FAMILLE du vin à partir de l'appellation (cf. tables).
Étape 2 : récupère le triplet d'offsets (début, apogée min..max, garde max).
Étape 3 : applique les modificateurs qualité (cuvée prestige, GCC, vieilles
          vignes, etc. — voir section MODIFICATEURS).
Étape 4 : applique le modificateur millésime si tu le connais (cf. section
          MILLÉSIMES). Sinon laisse tel quel.
Étape 5 : additionne offsets et M, vérifie la cohérence, écris le JSON.

==============================================================================
TABLES D'OFFSETS — par grande famille
Format : début | apogée_min .. apogée_max | garde_max  (tous en années après M)
==============================================================================

FRANCE — ROUGES

Beaujolais
  Beaujolais Nouveau / Primeur                  | M+0 | M+0..M+1   | M+2
  Beaujolais AOC                                | M+1 | M+1..M+3   | M+5
  Beaujolais Villages                           | M+1 | M+2..M+4   | M+6
  Beaujolais cru léger (Brouilly, Chiroubles,   | M+2 | M+3..M+6   | M+8
    Régnié)
  Beaujolais cru classique (Côte de Brouilly,   | M+2 | M+4..M+8   | M+10
    Fleurie, Saint-Amour, Juliénas)
  Beaujolais cru structuré (Morgon, Chénas,     | M+3 | M+5..M+10  | M+15
    Moulin-à-Vent)

Bourgogne rouge
  Bourgogne régional (Bourgogne AOC, Hautes-    | M+2 | M+3..M+5   | M+8
    Côtes, Coteaux Bourguignons)
  Bourgogne village Côte de Beaune (Pommard,    | M+4 | M+6..M+10  | M+15
    Volnay, Beaune, Santenay)
  Bourgogne village Côte de Nuits (Gevrey,      | M+5 | M+8..M+12  | M+18
    Nuits-St-Georges, Vosne, Morey, Chambolle)
  Bourgogne 1er Cru Côte de Beaune              | M+5 | M+8..M+15  | M+20
  Bourgogne 1er Cru Côte de Nuits               | M+7 | M+10..M+18 | M+25
  Bourgogne Grand Cru Côte de Beaune (Corton)   | M+7 | M+12..M+20 | M+30
  Bourgogne Grand Cru Côte de Nuits (Chambertin,| M+10| M+15..M+25 | M+40
    Romanée-Conti, Musigny, Clos de Vougeot…)

Bordeaux rouge
  Bordeaux AOC / Bordeaux Supérieur             | M+2 | M+3..M+6   | M+8
  Côtes de Bordeaux (Blaye, Castillon, Francs)  | M+3 | M+4..M+7   | M+10
  Médoc / Haut-Médoc générique                  | M+3 | M+5..M+10  | M+15
  Saint-Émilion satellite (Lussac, Montagne,    | M+3 | M+4..M+8   | M+12
    Puisseguin)
  Cru Bourgeois Médoc                           | M+4 | M+6..M+12  | M+18
  Saint-Émilion Grand Cru                       | M+4 | M+7..M+15  | M+20
  Pomerol courant                               | M+5 | M+8..M+15  | M+22
  Grand Cru Classé Médoc (2e à 5e cru)          | M+6 | M+10..M+20 | M+30
  Pessac-Léognan classé                         | M+6 | M+10..M+18 | M+28
  1er Grand Cru Classé Médoc (Latour, Margaux,  | M+10| M+15..M+30 | M+50
    Mouton, Lafite, Haut-Brion)
  Pomerol prestige (Petrus, Le Pin) / Saint-Em. | M+10| M+15..M+30 | M+45
    1er GCC A (Ausone, Cheval Blanc)

Rhône rouge
  Côtes du Rhône AOC                            | M+1 | M+2..M+4   | M+6
  Côtes du Rhône Villages génériques            | M+2 | M+3..M+6   | M+8
  Côtes du Rhône Villages nommés (Cairanne…)    | M+3 | M+4..M+8   | M+12
  Vacqueyras / Lirac / Rasteau                  | M+3 | M+5..M+8   | M+12
  Gigondas                                      | M+4 | M+6..M+12  | M+18
  Châteauneuf-du-Pape                           | M+5 | M+8..M+15  | M+25
  Crozes-Hermitage                              | M+3 | M+5..M+10  | M+15
  Saint-Joseph                                  | M+3 | M+5..M+10  | M+15
  Cornas                                        | M+5 | M+8..M+15  | M+25
  Côte-Rôtie                                    | M+5 | M+10..M+18 | M+25
  Hermitage rouge                               | M+7 | M+10..M+20 | M+35

Loire rouge
  Touraine / Anjou rouge AOC                    | M+1 | M+2..M+4   | M+6
  Chinon, Bourgueil, Saint-Nicolas-de-Bourgueil | M+2 | M+3..M+6   | M+10
  Chinon vieilles vignes / cuvées élevées       | M+3 | M+5..M+10  | M+15
  Saumur-Champigny                              | M+2 | M+3..M+6   | M+10
  Sancerre rouge                                | M+2 | M+3..M+5   | M+8

Languedoc-Roussillon rouge
  Pays d'Oc IGP / Languedoc générique           | M+1 | M+1..M+3   | M+5
  Languedoc Villages (Pic Saint-Loup, Faugères, | M+2 | M+3..M+6   | M+10
    Saint-Chinian, La Clape)
  Minervois La Livinière / Corbières-Boutenac   | M+2 | M+4..M+8   | M+12
  Côtes du Roussillon Villages                  | M+2 | M+3..M+6   | M+10
  Maury sec / Banyuls sec                       | M+3 | M+5..M+10  | M+15

Sud-Ouest rouge
  Bergerac, Buzet, Côtes du Marmandais          | M+2 | M+3..M+5   | M+8
  Cahors, Madiran, Saint-Mont, Fronton          | M+3 | M+5..M+10  | M+15

Provence rouge
  Côtes de Provence / Coteaux d'Aix             | M+1 | M+2..M+4   | M+6
  Bandol                                        | M+4 | M+6..M+12  | M+20

Jura / Savoie / Alpes rouge
  Mondeuse de Savoie                            | M+1 | M+2..M+4   | M+6
  Arbois / Côtes du Jura (Poulsard, Trousseau)  | M+2 | M+3..M+6   | M+10
  Arbois Pinot Noir                             | M+2 | M+4..M+8   | M+12

FRANCE — BLANCS

Bourgogne blanc
  Bourgogne régional / Aligoté                  | M+0 | M+1..M+2   | M+4
  Mâcon, Mâcon-Villages                         | M+1 | M+1..M+3   | M+5
  Pouilly-Fuissé, Saint-Véran, Viré-Clessé      | M+2 | M+3..M+5   | M+8
  Chablis AOC                                   | M+1 | M+2..M+4   | M+6
  Chablis 1er Cru                               | M+2 | M+3..M+8   | M+12
  Chablis Grand Cru                             | M+3 | M+5..M+12  | M+20
  Village Côte de Beaune blanc (Meursault,      | M+2 | M+4..M+8   | M+15
    Puligny-Montrachet, Chassagne-Montrachet,
    Saint-Aubin, Saint-Romain)
  1er Cru Côte de Beaune blanc                  | M+3 | M+5..M+12  | M+20
  Grand Cru Côte de Beaune blanc (Montrachet,   | M+5 | M+8..M+18  | M+30
    Corton-Charlemagne, Bâtard-Montrachet)

Loire blanc
  Muscadet AOC                                  | M+0 | M+0..M+2   | M+4
  Muscadet Sèvre-et-Maine sur lie élevage long  | M+1 | M+2..M+5   | M+10
  Sancerre / Pouilly-Fumé / Menetou-Salon       | M+1 | M+2..M+4   | M+6
  Sancerre cuvée prestige / vieilles vignes     | M+2 | M+3..M+8   | M+12
  Vouvray sec                                   | M+2 | M+3..M+8   | M+15
  Vouvray demi-sec / moelleux                   | M+3 | M+5..M+15  | M+30
  Savennières                                   | M+3 | M+5..M+10  | M+20
  Coteaux du Layon, Quarts de Chaume, Bonnezeaux| M+5 | M+8..M+20  | M+40
  Saumur blanc                                  | M+1 | M+2..M+4   | M+8

Bordeaux blanc
  Bordeaux blanc AOC sec                        | M+1 | M+1..M+3   | M+5
  Entre-Deux-Mers                               | M+0 | M+1..M+2   | M+4
  Pessac-Léognan / Graves blanc générique       | M+2 | M+3..M+6   | M+10
  Pessac-Léognan classé blanc (Haut-Brion,      | M+3 | M+5..M+12  | M+20
    Smith Haut Lafitte, Carbonnieux blanc…)
  Sauternes / Barsac générique                  | M+5 | M+10..M+25 | M+50
  Sauternes premier cru (Yquem, Suduiraut,      | M+8 | M+15..M+40 | M+80
    Rieussec, Climens, La Tour Blanche)
  Loupiac / Sainte-Croix-du-Mont                | M+3 | M+5..M+15  | M+25
  Cadillac liquoreux                            | M+3 | M+5..M+12  | M+20

Rhône blanc
  Côtes du Rhône blanc                          | M+0 | M+1..M+3   | M+5
  Côtes du Rhône Villages blanc                 | M+1 | M+2..M+4   | M+7
  Châteauneuf-du-Pape blanc                     | M+2 | M+3..M+8   | M+15
  Condrieu                                      | M+1 | M+2..M+4   | M+8
  Hermitage blanc                               | M+5 | M+10..M+20 | M+40

Alsace blanc
  Sylvaner, Pinot Blanc, Edelzwicker            | M+1 | M+1..M+3   | M+5
  Pinot Gris d'Alsace                           | M+2 | M+3..M+6   | M+10
  Gewurztraminer                                | M+2 | M+3..M+8   | M+15
  Riesling d'Alsace                             | M+2 | M+3..M+8   | M+15
  Riesling Grand Cru                            | M+3 | M+5..M+15  | M+25
  Vendanges Tardives / Sélection Grains Nobles  | M+5 | M+8..M+20  | M+40

Languedoc / Provence / Sud blanc
  Picpoul de Pinet                              | M+0 | M+0..M+2   | M+3
  Languedoc blanc générique                     | M+0 | M+1..M+3   | M+5
  Cassis blanc, Bandol blanc                    | M+1 | M+2..M+4   | M+8

Savoie / Jura blanc
  Roussette de Savoie, Apremont, Chignin        | M+0 | M+1..M+3   | M+5
  Vin Jaune (Château-Chalon)                    | M+10| M+15..M+50 | M+100
  Chardonnay du Jura, Savagnin ouillé           | M+2 | M+3..M+8   | M+15

FRANCE — ROSÉS
  Rosé de Provence, Côtes du Rhône rosé, IGP    | M+0 | M+0..M+1   | M+2
  Tavel                                         | M+0 | M+1..M+2   | M+4
  Bandol rosé                                   | M+1 | M+2..M+4   | M+6
  Rosé d'Anjou, Cabernet d'Anjou (demi-sec)     | M+0 | M+1..M+2   | M+3

FRANCE — EFFERVESCENTS
  Crémant (Loire, Bourgogne, Alsace, Limoux,    | M+1 | M+2..M+4   | M+6
    Jura)
  Champagne brut sans année (BSA)               | M+2 | M+3..M+5   | M+8
    Note BSA : utilise l'année d'achat comme M
  Champagne millésimé courant                   | M+5 | M+8..M+15  | M+25
  Champagne millésimé prestige (Krug,           | M+8 | M+12..M+25 | M+40
    Dom Pérignon, Cristal, Salon, Comtes de
    Champagne, Belle Époque, Grande Dame)
  Champagne rosé millésimé                      | M+5 | M+8..M+15  | M+25

==============================================================================
TABLES D'OFFSETS — étranger (à connaître)
==============================================================================

ITALIE
  Chianti DOCG                                  | M+2 | M+3..M+6   | M+10
  Chianti Classico Riserva / Gran Selezione     | M+3 | M+5..M+12  | M+20
  Brunello di Montalcino                        | M+5 | M+8..M+18  | M+30
  Brunello di Montalcino Riserva                | M+7 | M+10..M+25 | M+40
  Vino Nobile di Montepulciano                  | M+3 | M+5..M+12  | M+20
  Super Tuscan (Sassicaia, Ornellaia, Tignanello| M+5 | M+8..M+18  | M+30
  Barolo / Barbaresco                           | M+7 | M+10..M+20 | M+35
  Barolo / Barbaresco Riserva                   | M+10| M+15..M+30 | M+50
  Valpolicella                                  | M+1 | M+2..M+4   | M+8
  Valpolicella Ripasso                          | M+2 | M+3..M+8   | M+12
  Amarone della Valpolicella                    | M+5 | M+8..M+15  | M+25
  Primitivo, Negroamaro                         | M+1 | M+2..M+5   | M+8
  Pinot Grigio, Soave, Verdicchio (blanc)       | M+0 | M+1..M+2   | M+4
  Vermentino, Gavi (blanc)                      | M+0 | M+1..M+3   | M+5

ESPAGNE
  Rioja Crianza                                 | M+2 | M+3..M+6   | M+10
  Rioja Reserva                                 | M+3 | M+5..M+10  | M+15
  Rioja Gran Reserva                            | M+5 | M+8..M+15  | M+25
  Ribera del Duero Crianza                      | M+3 | M+4..M+8   | M+12
  Ribera del Duero Reserva                      | M+5 | M+7..M+12  | M+20
  Ribera del Duero Gran Reserva / Vega Sicilia  | M+10| M+15..M+30 | M+50
  Priorat                                       | M+5 | M+8..M+15  | M+25
  Cava                                          | M+1 | M+2..M+4   | M+6
  Albariño, Verdejo, Godello                    | M+0 | M+1..M+2   | M+4

PORTUGAL
  Vinho Verde                                   | M+0 | M+0..M+1   | M+2
  Douro rouge                                   | M+3 | M+5..M+10  | M+15
  Porto Tawny (10, 20, 30, 40 ans — déjà mûr)   | M+0 | M+0..M+5   | M+15
  Porto LBV (Late Bottled Vintage)              | M+3 | M+5..M+15  | M+25
  Porto Vintage                                 | M+10| M+20..M+40 | M+80

NOUVEAU MONDE — ROUGES
  Californie Pinot Noir (Sonoma, Russian River) | M+2 | M+3..M+8   | M+12
  Californie Zinfandel                          | M+2 | M+3..M+6   | M+10
  Californie Cabernet Sauvignon Napa Valley     | M+5 | M+8..M+15  | M+25
  Californie Cabernet « cult wine » (Screaming  | M+8 | M+12..M+25 | M+40
    Eagle, Harlan, Opus One)
  Oregon Pinot Noir                             | M+3 | M+5..M+10  | M+15
  Washington Cabernet / Syrah                   | M+3 | M+5..M+10  | M+15
  Australie Shiraz Barossa, McLaren Vale        | M+3 | M+5..M+10  | M+15
  Australie Shiraz Penfolds Grange              | M+8 | M+15..M+30 | M+50
  Argentine Malbec Mendoza                      | M+2 | M+3..M+6   | M+10
  Chili Cabernet, Carmenère, Syrah              | M+2 | M+3..M+6   | M+10
  Nouvelle-Zélande Pinot Noir (Central Otago,   | M+2 | M+3..M+6   | M+10
    Martinborough)
  Afrique du Sud Pinotage, Stellenbosch rouge   | M+3 | M+4..M+8   | M+12

NOUVEAU MONDE — BLANCS
  Nouvelle-Zélande Sauvignon Blanc Marlborough  | M+0 | M+1..M+2   | M+4
  Australie Chardonnay (Margaret River, Yarra)  | M+1 | M+2..M+5   | M+8
  Australie Riesling Clare / Eden Valley        | M+2 | M+3..M+8   | M+15
  Californie Chardonnay Napa / Sonoma           | M+1 | M+2..M+5   | M+10

ALLEMAGNE / AUTRICHE
  Riesling Mosel / Rheingau Kabinett            | M+2 | M+3..M+8   | M+15
  Riesling Spätlese / Auslese                   | M+3 | M+5..M+12  | M+25
  Riesling Trockenbeerenauslese (TBA), Eiswein  | M+5 | M+10..M+30 | M+50
  Grüner Veltliner Wachau Smaragd               | M+2 | M+3..M+6   | M+10

==============================================================================
MODIFICATEURS QUALITÉ (appliqués APRÈS le choix de la ligne)
==============================================================================

À AJOUTER aux offsets (vin meilleur que la moyenne de sa famille) :
- Mention « Grand Cru », « 1er Cru », « Premier Cru »  → +30 % sur apogée et garde
- Mention « Réserve », « Gran Reserva », « Cuvée prestige » → +20 %
- Mention « Vieilles Vignes », « V.V. »                  → +15 %
- Domaine de réputation reconnue dans son appellation    → +15-20 %

À RETIRER (vin plus léger que la moyenne) :
- Mention « Primeur », « Nouveau »                       → ramène à M+0 / M+0..M+1 / M+2
- Mention « Léger », « Soif »                            → -20 %
- IGP / Vin de Pays sans précision                       → -15 %

==============================================================================
MODIFICATEURS MILLÉSIME (FRANCE — applique si tu le connais)
==============================================================================

Millésimes « grands » (peuvent porter +20 % sur la garde max) :
  Bordeaux : 2005, 2009, 2010, 2015, 2016, 2018, 2019, 2020, 2022
  Bourgogne rouge : 2005, 2009, 2010, 2015, 2017, 2019, 2020, 2022
  Bourgogne blanc : 2014, 2017, 2019, 2020, 2022
  Champagne : 2002, 2008, 2012, 2018
  Rhône Nord : 2009, 2010, 2015, 2017, 2018, 2019, 2020
  Rhône Sud (CDP) : 2007, 2010, 2015, 2016, 2019, 2020

Millésimes « difficiles » (-15 % sur l'apogée et la garde) :
  Bordeaux : 2002, 2007, 2013, 2017 (gel), 2021 (mildiou)
  Bourgogne : 2004, 2008, 2013, 2021 (gel)
  Rhône : 2002, 2014, 2021

Si tu ne sais pas — n'applique pas de modificateur.

==============================================================================
FALLBACK quand l'appellation est totalement inconnue
==============================================================================

Si l'appellation ne te dit absolument rien, prends ces valeurs par défaut
selon le TYPE renseigné :
  Rouge      → M+2 | M+3..M+6  | M+10  (Bordeaux générique)
  Blanc      → M+1 | M+1..M+3  | M+5   (Bourgogne blanc régional)
  Rosé       → M+0 | M+0..M+1  | M+2
  Champagne  → M+2 | M+3..M+5  | M+8

Si NI appellation NI type → null/[] partout, summary = "Informations
insuffisantes pour un avis fiable."

==============================================================================
EXEMPLES COMPLETS (pour calibrer ton raisonnement)
==============================================================================

Exemple 1 : Moulin-à-Vent, Domaine Gérard Boyer, 2023, Rouge
→ Famille = Beaujolais cru structuré (Moulin-à-Vent). Offsets : M+3 | M+5..M+10 | M+15
→ Pas de mention prestige → pas de modificateur.
→ 2023 + 3 = 2026 ; 2023 + 5 = 2028 ; 2023 + 10 = 2033 ; 2023 + 15 = 2038.

{
  "summary": "Le Moulin-à-Vent du Domaine Gérard Boyer 2023 est le Beaujolais \
cru le plus structuré, avec une robe rubis profond, un nez de fruits noirs, \
violette et épices douces. Sa trame tannique fine s'arrondit avec quelques \
années de cave et lui donne une vraie capacité de garde.",
  "apogee_year_min": 2028,
  "apogee_year_max": 2033,
  "keeping_year_max": 2038,
  "pairings_ideal": ["coq au vin", "bœuf bourguignon", "civet de lièvre"],
  "pairings_possible": ["volaille rôtie", "tomme de Savoie", \
"magret de canard", "plateau de charcuterie", "lapin à la moutarde"]
}

Exemple 2 : Sancerre blanc, Henri Bourgeois, 2022, Blanc
→ Famille = Sancerre / Pouilly-Fumé blanc. Offsets : M+1 | M+2..M+4 | M+6
→ 2022 + 1 = 2023 ; 2022 + 2 = 2024 ; 2022 + 4 = 2026 ; 2022 + 6 = 2028.

{
  "summary": "Le Sancerre d'Henri Bourgeois 2022 est un blanc sec vif, à la \
robe pâle, sur des arômes d'agrumes, de buis et de pierre à fusil. Bouche \
nerveuse et minérale, à boire jeune pour profiter de sa fraîcheur.",
  "apogee_year_min": 2024,
  "apogee_year_max": 2026,
  "keeping_year_max": 2028,
  "pairings_ideal": ["crottin de Chavignol", "asperges vertes", \
"sole meunière"],
  "pairings_possible": ["huîtres", "salade de chèvre chaud", \
"poulet rôti", "ceviche de daurade"]
}

Exemple 3 : Château Margaux, 2015, Rouge
→ Famille = 1er Grand Cru Classé Médoc. Offsets : M+10 | M+15..M+30 | M+50
→ 2015 est un millésime « grand » à Bordeaux → +20 % sur la garde max.
→ 2015 + 10 = 2025 ; 2015 + 15 = 2030 ; 2015 + 30 = 2045 ;
  2015 + 50 = 2065 ; +20 % ≈ 2075 (on plafonne à ~2070, raisonnable).

{
  "summary": "Château Margaux 2015 est un premier grand cru classé d'une \
finesse rare, sur un millésime considéré comme grand. Robe profonde, nez \
complexe de fruits noirs, cèdre et épices nobles, bouche soyeuse aux \
tannins fondus mais persistants.",
  "apogee_year_min": 2030,
  "apogee_year_max": 2045,
  "keeping_year_max": 2070,
  "pairings_ideal": ["côte de bœuf", "agneau de Pauillac", \
"pigeon rôti aux truffes"],
  "pairings_possible": ["filet de bœuf au foie gras", "magret de canard", \
"plateau de fromages affinés"]
}

==============================================================================
RÈGLES FINALES
==============================================================================

- Pas de markdown, pas de texte autour, UNIQUEMENT le JSON.
- Années entre 1990 et 2080.
- apogee_year_min ≤ apogee_year_max ≤ keeping_year_max — toujours.
- Si tu reconnais la famille → tu DOIS produire les trois années.
- Si tu n'as aucune idée du vin → null/[] partout + summary insuffisant.
- pairings_ideal : 3 à 5 plats qui mettent le vin en valeur.
- pairings_possible : 3 à 6 plats qui marchent bien sans être parfaits.
- Plats en français, en minuscules sauf noms propres.
"""


@dataclass
class WineEnrichment:
    summary: str
    apogee_year_min: int | None
    apogee_year_max: int | None
    keeping_year_max: int | None
    pairings_ideal: list[str]
    pairings_possible: list[str]


class EnrichmentError(Exception):
    """Raised when the LLM call cannot produce a usable result."""


class EnrichmentDisabled(EnrichmentError):
    """Raised when no LLM is configured — endpoint maps this to 503."""


def _ollama_url() -> str | None:
    base = os.environ.get("OLLAMA_BASE_URL", DEFAULT_BASE_URL).strip()
    return base or None


def _model_name() -> str:
    return os.environ.get("OLLAMA_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL


# Expose the resolved model so the router can record it as the
# enrichment source on the persisted wine row.
MODEL = _model_name()


def _build_user_prompt(
    *,
    appellation: str | None,
    domaine: str | None,
    millesime: int | None,
    type_: str | None,
) -> str:
    parts: list[str] = []
    if appellation:
        parts.append(f"Appellation : {appellation}")
    if domaine:
        parts.append(f"Domaine : {domaine}")
    if millesime:
        parts.append(f"Millésime : {millesime}")
    if type_:
        parts.append(f"Type : {type_}")
    if not parts:
        parts.append("(aucune information précise fournie)")
    return "Vin à analyser :\n" + "\n".join(parts)


def enrich_wine(
    *,
    appellation: str | None,
    domaine: str | None,
    millesime: int | None,
    type_: str | None,
) -> WineEnrichment:
    """Synchronous call to Ollama. Returns the parsed enrichment or raises."""
    base_url = _ollama_url()
    if not base_url:
        raise EnrichmentDisabled(
            "OLLAMA_BASE_URL n'est pas configuré côté serveur."
        )

    model = _model_name()
    user_prompt = _build_user_prompt(
        appellation=appellation,
        domaine=domaine,
        millesime=millesime,
        type_=type_,
    )
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        # Forces the model to emit a well-formed JSON document — Ollama
        # uses grammar-constrained decoding under the hood.
        "format": "json",
        "stream": False,
        # Low temperature: we want stable, factual sommelier output, not
        # creative writing. num_ctx must hold the ~6k-token system prompt
        # plus user message plus headroom for the JSON answer — Ollama's
        # default is 2048 which would silently truncate the guide and
        # make Mistral hallucinate. num_predict caps the answer length
        # so a runaway generation can't hang the request.
        "options": {
            "temperature": 0.2,
            "num_ctx": 8192,
            "num_predict": 800,
        },
    }

    log.info("Calling Ollama at %s with model %s", base_url, model)
    try:
        # Split timeouts: connection should be fast (LAN), but reads can
        # be slow on a cold-start 7B inference. Httpx defaults to a single
        # timeout for everything, which made cold starts trip the wrong
        # error message in earlier builds.
        timeout = httpx.Timeout(connect=10.0, read=CALL_TIMEOUT_SECONDS,
                                write=10.0, pool=10.0)
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(f"{base_url}/api/chat", json=payload)
    except httpx.ConnectError as exc:
        raise EnrichmentError(
            "Ollama injoignable. Vérifie que le conteneur homestock-ollama "
            f"tourne (docker ps) et est joignable sur {base_url}."
        ) from exc
    except httpx.ReadTimeout as exc:
        raise EnrichmentError(
            f"Ollama n'a pas répondu en {int(CALL_TIMEOUT_SECONDS)}s. "
            "Le modèle finit peut-être de charger en RAM ; réessaie dans "
            "une minute. Si ça persiste, le NAS manque de RAM pour ce modèle "
            "— bascule sur OLLAMA_MODEL=llama3.2:3b."
        ) from exc
    except httpx.HTTPError as exc:
        log.exception("Ollama call failed")
        raise EnrichmentError(f"Appel LLM échoué : {exc}") from exc

    if resp.status_code == 404:
        # Most likely "model not found" — surface a helpful hint.
        raise EnrichmentError(
            f"Modèle « {model} » indisponible sur Ollama. "
            f"Lance « docker exec homestock-ollama ollama pull {model} »."
        )
    if resp.status_code >= 400:
        raise EnrichmentError(
            f"Ollama a renvoyé {resp.status_code} : {resp.text[:200]}"
        )

    try:
        body = resp.json()
    except json.JSONDecodeError as exc:
        raise EnrichmentError(f"Réponse Ollama invalide : {exc}") from exc

    content = (body.get("message") or {}).get("content", "").strip()
    if not content:
        raise EnrichmentError("Réponse vide d'Ollama.")

    # The `format: "json"` mode should guarantee valid JSON, but small
    # models occasionally wrap it in ``` fences — strip them defensively.
    if content.startswith("```"):
        content = content.strip("`")
        if content.lower().startswith("json"):
            content = content[4:].lstrip()

    try:
        parsed = json.loads(content)
    except json.JSONDecodeError as exc:
        log.warning("Ollama returned non-JSON: %r", content[:200])
        raise EnrichmentError(f"JSON invalide du modèle : {exc}") from exc

    apogee_min = _safe_year(parsed.get("apogee_year_min"))
    apogee_max = _safe_year(parsed.get("apogee_year_max"))
    keep_max = _safe_year(parsed.get("keeping_year_max"))

    # Sanity-check against the millésime so a 7B model hallucinating
    # "apogée 2036-2048 for a 2023 wine" doesn't pollute the database. If
    # any of the years sits more than 35 years after the vintage, we drop
    # all three rather than persisting nonsense — the user can re-try.
    if millesime and apogee_min and apogee_min > millesime + 35:
        log.warning(
            "Dropping suspiciously distant apogée_min %d for millésime %d",
            apogee_min, millesime,
        )
        apogee_min = apogee_max = keep_max = None
    elif apogee_min and apogee_max and apogee_min > apogee_max:
        log.warning("apogee_min > apogee_max (%d > %d), dropping", apogee_min, apogee_max)
        apogee_min = apogee_max = None

    return WineEnrichment(
        summary=str(parsed.get("summary") or "").strip(),
        apogee_year_min=apogee_min,
        apogee_year_max=apogee_max,
        keeping_year_max=keep_max,
        pairings_ideal=_safe_str_list(parsed.get("pairings_ideal")),
        pairings_possible=_safe_str_list(parsed.get("pairings_possible")),
    )


def _safe_year(v) -> int | None:
    if v is None:
        return None
    try:
        year = int(v)
    except (ValueError, TypeError):
        return None
    if 1900 <= year <= 2100:
        return year
    return None


def _safe_str_list(v) -> list[str]:
    if not isinstance(v, list):
        return []
    return [str(x).strip() for x in v if str(x).strip()]
