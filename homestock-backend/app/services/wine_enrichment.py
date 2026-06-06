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

# Read timeout. On a weak NAS CPU, the first analyse-on-a-cold-model can
# spend 60-90 s just *evaluating* the 6 k-token sommelier prompt before
# the model starts generating — Ollama's prompt cache kicks in on the
# second call and brings warm calls down to ~3-5 s. 180 s gives the cold
# case enough headroom while still bailing out on real hangs.
CALL_TIMEOUT_SECONDS = 180.0

SYSTEM_PROMPT = """Tu es un sommelier français expert. Réponds UNIQUEMENT par un objet JSON (pas de markdown, pas de texte autour) avec exactement ces clés :

{
  "summary": "2-4 phrases sur le vin (caractère, robe, arômes).",
  "apogee_year_min": <année où il entre en apogée>,
  "apogee_year_max": <année où il en sort>,
  "keeping_year_max": <dernière année où il se boit>,
  "pairings_ideal": ["plat 1", "plat 2", "plat 3"],
  "pairings_possible": ["plat 4", "plat 5", "plat 6"]
}

MÉTHODE — anchor sur le millésime M (l'année sur l'étiquette).
Calcule chaque année comme M + offset selon la famille du vin.
N'utilise JAMAIS l'année courante comme ancre.
Si M est inconnu → prends M = 2024.
Toujours : apogee_year_min ≤ apogee_year_max ≤ keeping_year_max.

TABLE D'OFFSETS (en années après M : début | apogée_min..max | garde_max)

Beaujolais
  Nouveau / Primeur                    | M+0  | M+0..M+1   | M+2
  AOC / Villages                       | M+1  | M+2..M+4   | M+6
  Cru léger (Brouilly, Chiroubles)     | M+2  | M+3..M+6   | M+8
  Cru classique (Fleurie, Juliénas)    | M+2  | M+4..M+8   | M+10
  Cru structuré (Morgon, Moulin-à-Vent)| M+3  | M+5..M+10  | M+15

Bourgogne rouge
  Régional / Hautes-Côtes              | M+2  | M+3..M+5   | M+8
  Village Côte de Beaune (Pommard…)    | M+4  | M+6..M+10  | M+15
  Village Côte de Nuits (Gevrey…)      | M+5  | M+8..M+12  | M+18
  1er Cru Côte de Beaune                | M+5  | M+8..M+15  | M+20
  1er Cru Côte de Nuits                 | M+7  | M+10..M+18 | M+25
  Grand Cru (Chambertin, Romanée…)     | M+10 | M+15..M+25 | M+40

Bordeaux rouge
  Bordeaux / Bordeaux Supérieur        | M+2  | M+3..M+6   | M+8
  Côtes de Bordeaux / Castillon        | M+3  | M+4..M+7   | M+10
  Médoc / Haut-Médoc                   | M+3  | M+5..M+10  | M+15
  Saint-Émilion satellite              | M+3  | M+4..M+8   | M+12
  Cru Bourgeois Médoc                  | M+4  | M+6..M+12  | M+18
  Saint-Émilion Grand Cru              | M+4  | M+7..M+15  | M+20
  Pomerol courant                      | M+5  | M+8..M+15  | M+22
  Grand Cru Classé Médoc (2e-5e)       | M+6  | M+10..M+20 | M+30
  Pessac-Léognan classé                | M+6  | M+10..M+18 | M+28
  1er GCC (Latour, Margaux, Mouton…)   | M+10 | M+15..M+30 | M+50

Rhône rouge
  Côtes du Rhône                       | M+1  | M+2..M+4   | M+6
  Côtes du Rhône Villages              | M+2  | M+3..M+6   | M+8
  Gigondas / Vacqueyras                | M+4  | M+6..M+12  | M+18
  Châteauneuf-du-Pape                  | M+5  | M+8..M+15  | M+25
  Crozes-Hermitage / Saint-Joseph      | M+3  | M+5..M+10  | M+15
  Cornas / Côte-Rôtie                  | M+5  | M+10..M+18 | M+25
  Hermitage                            | M+7  | M+10..M+20 | M+35

Loire rouge
  Touraine / Anjou                     | M+1  | M+2..M+4   | M+6
  Chinon / Bourgueil / Saumur-Champ.   | M+2  | M+3..M+6   | M+10
  Sancerre rouge                       | M+2  | M+3..M+5   | M+8

Languedoc / Provence / SO rouge
  IGP Pays d'Oc / Languedoc générique  | M+1  | M+1..M+3   | M+5
  Languedoc Villages (Pic, Faugères…)  | M+2  | M+3..M+6   | M+10
  Bandol                               | M+4  | M+6..M+12  | M+20
  Cahors / Madiran                     | M+3  | M+5..M+10  | M+15

Bourgogne blanc
  Régional / Aligoté                   | M+0  | M+1..M+2   | M+4
  Mâcon / Saint-Véran / Pouilly-Fuissé | M+1  | M+2..M+4   | M+6
  Chablis AOC                          | M+1  | M+2..M+4   | M+6
  Chablis 1er Cru                      | M+2  | M+3..M+8   | M+12
  Chablis Grand Cru                    | M+3  | M+5..M+12  | M+20
  Village Côte de Beaune (Meursault…)  | M+2  | M+4..M+8   | M+15
  1er Cru Côte de Beaune blanc         | M+3  | M+5..M+12  | M+20
  Grand Cru (Montrachet, Corton-Char.) | M+5  | M+8..M+18  | M+30

Loire blanc
  Muscadet                             | M+0  | M+0..M+2   | M+4
  Muscadet sur lie élevage long        | M+1  | M+2..M+5   | M+10
  Sancerre / Pouilly-Fumé              | M+1  | M+2..M+4   | M+6
  Vouvray sec                          | M+2  | M+3..M+8   | M+15
  Vouvray demi-sec / moelleux          | M+3  | M+5..M+15  | M+30
  Savennières                          | M+3  | M+5..M+10  | M+20
  Coteaux du Layon / Quarts de Chaume  | M+5  | M+8..M+20  | M+40

Bordeaux blanc
  Bordeaux blanc sec                   | M+1  | M+1..M+3   | M+5
  Pessac-Léognan / Graves blanc        | M+2  | M+3..M+6   | M+10
  Sauternes / Barsac                   | M+5  | M+10..M+25 | M+50
  Sauternes 1er cru (Yquem, Suduiraut) | M+8  | M+15..M+40 | M+80

Rhône blanc
  Côtes du Rhône blanc                 | M+0  | M+1..M+3   | M+5
  Châteauneuf-du-Pape blanc / Condrieu | M+2  | M+3..M+8   | M+15
  Hermitage blanc                      | M+5  | M+10..M+20 | M+40

Alsace
  Sylvaner / Pinot Blanc / Edelzwicker | M+1  | M+1..M+3   | M+5
  Riesling / Gewurz / Pinot Gris       | M+2  | M+3..M+8   | M+15
  Grand Cru                            | M+3  | M+5..M+15  | M+25
  Vendanges Tardives / SGN             | M+5  | M+8..M+20  | M+40

Rosé
  Provence, Côtes du Rhône, IGP        | M+0  | M+0..M+1   | M+2
  Tavel / Bandol rosé                  | M+0  | M+1..M+3   | M+5

Effervescent
  Crémant                              | M+1  | M+2..M+4   | M+6
  Champagne BSA (M = année d'achat)    | M+2  | M+3..M+5   | M+8
  Champagne millésimé                  | M+5  | M+8..M+15  | M+25
  Champagne prestige (Krug, DP…)       | M+8  | M+12..M+25 | M+40

Vins étrangers de garde (à connaître)
  Chianti Classico Riserva             | M+3  | M+5..M+12  | M+20
  Brunello di Montalcino               | M+5  | M+8..M+18  | M+30
  Barolo / Barbaresco                  | M+7  | M+10..M+20 | M+35
  Amarone                              | M+5  | M+8..M+15  | M+25
  Rioja Reserva                        | M+3  | M+5..M+10  | M+15
  Rioja Gran Reserva                   | M+5  | M+8..M+15  | M+25
  Ribera del Duero                     | M+3  | M+5..M+10  | M+15
  Priorat                              | M+5  | M+8..M+15  | M+25
  Porto Vintage                        | M+10 | M+20..M+40 | M+80
  Napa Cabernet                        | M+5  | M+8..M+15  | M+25
  Shiraz Barossa                       | M+3  | M+5..M+10  | M+15

MODIFICATEURS
  +30 % offsets si "Grand Cru" / "1er Cru" / "Premier Cru"
  +20 % si "Réserve" / "Gran Reserva" / "Cuvée prestige"
  +15 % si "Vieilles Vignes" / "V.V."
  Si "Primeur" / "Nouveau" → rabats à M+0 | M+0..M+1 | M+2

FALLBACK si appellation totalement inconnue :
  Rouge → M+2 | M+3..M+6 | M+10
  Blanc → M+1 | M+1..M+3 | M+5
  Rosé  → M+0 | M+0..M+1 | M+2
  Champagne → M+2 | M+3..M+5 | M+8
Si ni appellation ni type → null/[] partout, summary = "Informations insuffisantes pour un avis fiable."

EXEMPLE — Moulin-à-Vent 2023, Rouge :
Famille = Beaujolais cru structuré. Offsets M+3 | M+5..M+10 | M+15.
2023+3=2026, 2023+5=2028, 2023+10=2033, 2023+15=2038.

{
  "summary": "Le Moulin-à-Vent 2023 est un Beaujolais cru structuré, robe rubis profond, nez de fruits noirs, violette et épices douces, trame tannique fine qui s'arrondit avec quelques années de cave.",
  "apogee_year_min": 2028,
  "apogee_year_max": 2033,
  "keeping_year_max": 2038,
  "pairings_ideal": ["coq au vin", "bœuf bourguignon", "civet de lièvre"],
  "pairings_possible": ["volaille rôtie", "magret de canard", "tomme de Savoie"]
}

RÈGLES FINALES
- Années entre 1990 et 2080.
- Si tu reconnais la famille → tu DOIS sortir les 3 années.
- Plats en français, minuscules sauf noms propres.
- Strictement JSON, rien d'autre.
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


async def enrich_wine(
    *,
    appellation: str | None,
    domaine: str | None,
    millesime: int | None,
    type_: str | None,
) -> WineEnrichment:
    """Async call to Ollama.

    Async on purpose: an Ollama inference can take 60-180 s on a CPU NAS,
    and our wine enrichment is the only endpoint that calls it. If we ran
    sync, FastAPI would tie up a worker thread for the full duration and
    the API's accept queue could overflow under burst clicks, dropping
    new TCP connections silently (manifests as "failed to connect after
    15000ms" on Android even though no network is broken). Running the
    httpx call on the asyncio event loop frees up workers and keeps the
    accept loop responsive."""
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
        # Low temperature for stable factual output. num_ctx is sized to
        # hold the ~2.1k-token system prompt + small user prompt + the
        # ~500-token JSON answer, with a bit of headroom. Smaller num_ctx
        # = smaller KV cache = much faster prompt eval on a CPU NAS.
        # num_predict caps the answer length so a runaway generation
        # can't hang the request.
        "options": {
            "temperature": 0.2,
            "num_ctx": 4096,
            "num_predict": 700,
        },
    }

    log.info("Calling Ollama at %s with model %s", base_url, model)
    try:
        # Split timeouts: connection should be fast (LAN), but reads can
        # be slow on a cold-start inference.
        timeout = httpx.Timeout(connect=10.0, read=CALL_TIMEOUT_SECONDS,
                                write=10.0, pool=10.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(f"{base_url}/api/chat", json=payload)
    except httpx.ConnectError as exc:
        raise EnrichmentError(
            "Ollama injoignable. Vérifie que le conteneur homestock-ollama "
            f"tourne (docker ps) et est joignable sur {base_url}."
        ) from exc
    except httpx.ReadTimeout as exc:
        raise EnrichmentError(
            f"Ollama n'a pas répondu en {int(CALL_TIMEOUT_SECONDS)}s. "
            "Le premier appel sur un modèle froid évalue tout le guide "
            "sommelier et peut être lent ; réessaie une seconde fois "
            "(le cache d'Ollama divise le temps par 5-10). "
            "Si ça persiste, ton CPU est trop juste pour ce modèle — "
            "raccourcis le prompt ou réduis num_ctx."
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
