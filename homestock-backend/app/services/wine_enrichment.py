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
# Ollama or swap the model (e.g. llama3.2:3b on a smaller NAS).
DEFAULT_BASE_URL = "http://homestock-ollama:11434"
DEFAULT_MODEL = "mistral:7b-instruct"

# Generous timeout: with a 7B model, cold-start inference on CPU can take
# 60–90 s the first time the weights are paged into RAM, then drop to
# 10–25 s on a warm model. 180 s is a safety net for a NAS that just
# rebooted; warm calls are nowhere near this.
CALL_TIMEOUT_SECONDS = 180.0

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

Méthode d'estimation des années :
- Tu DOIS ancrer les trois années sur le millésime fourni (noté M).
  Calcule les années comme M + offset, où l'offset dépend de la famille du vin.
- N'utilise pas l'année courante comme point de départ : un vin de millésime
  2010 n'entre pas en apogée en 2035.
- Apogée est typiquement une fenêtre de 3 à 8 ans ; la date limite est
  toujours après l'apogée max.

Table de référence (offsets en années à partir du millésime M) :

| Famille                                   | À partir de | Apogée min..max | Garde max |
|-------------------------------------------|-------------|------------------|------------|
| Beaujolais Nouveau / Primeur              | M+0         | M+0 .. M+1       | M+2        |
| Beaujolais cru (Morgon, Moulin-à-Vent…)   | M+2         | M+4 .. M+8       | M+12       |
| Bourgogne village rouge                   | M+3         | M+5 .. M+8       | M+12       |
| Bourgogne 1er/grand cru rouge             | M+5         | M+8 .. M+15      | M+25       |
| Bordeaux générique / Côtes de Bordeaux    | M+2         | M+3 .. M+6       | M+10       |
| Bordeaux Saint-Émilion / Pomerol courant  | M+3         | M+5 .. M+10      | M+15       |
| Bordeaux grand cru classé / 2e vin GCC    | M+5         | M+10 .. M+20     | M+30       |
| Côtes du Rhône village                    | M+2         | M+3 .. M+6       | M+10       |
| Châteauneuf-du-Pape, Hermitage            | M+5         | M+8 .. M+15      | M+20       |
| Loire rouge léger (Chinon, Bourgueil)     | M+2         | M+3 .. M+6       | M+8        |
| Vin blanc sec courant                     | M+1         | M+1 .. M+2       | M+4        |
| Bourgogne blanc village                   | M+2         | M+3 .. M+6       | M+10       |
| Bourgogne blanc 1er/grand cru             | M+3         | M+5 .. M+12      | M+20       |
| Riesling / Alsace                         | M+2         | M+5 .. M+12      | M+18       |
| Champagne non-millésimé                   | M+2         | M+3 .. M+6       | M+8        |
| Champagne millésimé                       | M+5         | M+8 .. M+15      | M+20       |
| Sauternes / liquoreux                     | M+5         | M+10 .. M+25     | M+40       |
| Rosé                                      | M+0         | M+0 .. M+2       | M+3        |

Si tu ne reconnais pas la famille avec certitude, choisis l'estimation la
plus proche du TYPE et de la région ; à défaut, prends la ligne « générique »
du type concerné.

Exemple complet pour un Beaujolais cru millésime 2020 :
{
  "summary": "Le Moulin-à-Vent est le Beaujolais cru le plus structuré et le \
plus apte à la garde. Sur 2020, robe rubis profond, nez de fruits noirs, \
épices douces et une trame tannique fine qui s'arrondit avec le temps.",
  "apogee_year_min": 2024,
  "apogee_year_max": 2028,
  "keeping_year_max": 2032,
  "pairings_ideal": ["coq au vin", "bœuf bourguignon", "civet de lièvre"],
  "pairings_possible": ["volaille rôtie", "tomme de Savoie", "magret de canard", \
"plateau de charcuterie"]
}

Règles :
- Si tu reconnais l'appellation et le type, tu DOIS estimer les trois années \
en appliquant la table ci-dessus, même approximativement.
- Si tu ne reconnais PAS du tout l'appellation et que le millésime est absent, \
mets "summary" à "Informations insuffisantes pour un avis fiable." et laisse \
TOUS les autres champs null/[].
- Les années doivent être plausibles (entre 1990 et 2080) et cohérentes : \
apogee_year_min ≤ apogee_year_max ≤ keeping_year_max.
- pairings_ideal : 3 à 5 plats qui mettent le vin en valeur.
- pairings_possible : 3 à 6 plats qui marchent bien sans être parfaits.
- Les plats sont rédigés en français, en minuscules sauf noms propres.
- Pas d'explication hors du JSON, jamais de markdown."""


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
        # creative writing.
        "options": {"temperature": 0.2},
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
