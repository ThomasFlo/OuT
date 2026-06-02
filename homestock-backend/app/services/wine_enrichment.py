"""Wine enrichment via Anthropic Claude.

Given the user-provided wine basics (appellation, domaine, millésime, type),
ask Claude for a structured summary with drinking window and food pairings.
The endpoint that calls this is POST /vins/{id}/enrich; nothing else in the
app depends on the LLM being reachable, so missing credentials simply make
the endpoint return 503.
"""
from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass

log = logging.getLogger("homestock.wine_enrichment")

# Use the cheapest Claude model — wine enrichment is short, structured, and
# very tolerant of model size.
MODEL = "claude-haiku-4-5-20251001"

# The prompt is explicit about JSON output to avoid having to parse prose.
# Years are bounded so a model hallucination ("year 2200") becomes obvious.
SYSTEM_PROMPT = """Tu es un sommelier expert. À partir des informations de base \
sur un vin (appellation, domaine, millésime, type), tu retournes UNIQUEMENT \
un objet JSON valide (pas de texte autour, pas de markdown) avec ces clés :

{
  "summary": "Résumé sommelier en 2-4 phrases en français — caractère, robe, \
arômes typiques.",
  "apogee_year_min": <année entière où le vin entre dans sa fenêtre optimale>,
  "apogee_year_max": <année entière où il sort de cette fenêtre>,
  "keeping_year_max": <année limite au-delà de laquelle il ne se conserve plus>,
  "pairings_ideal": ["plat 1", "plat 2", "plat 3"],
  "pairings_possible": ["plat 4", "plat 5", "plat 6", "plat 7"]
}

Règles :
- Si tu ne reconnais pas l'appellation ou si les infos sont trop maigres, \
mets "summary" à "Informations insuffisantes pour un avis fiable." et laisse \
les autres champs null/[].
- Les années doivent être plausibles (entre 1990 et 2080).
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
    """Raised when no API key is configured — the endpoint maps this to 503."""


def _build_user_prompt(
    *,
    appellation: str | None,
    domaine: str | None,
    millesime: int | None,
    type_: str | None,
) -> str:
    """Compact prompt with only the fields the user filled in."""
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
    """Synchronous call to Claude. Returns the parsed enrichment or raises."""
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        raise EnrichmentDisabled(
            "ANTHROPIC_API_KEY n'est pas configuré côté serveur."
        )

    # Late import so the API container starts even when the SDK is missing
    # (e.g. on first install before pip install).
    try:
        from anthropic import Anthropic
    except ImportError as exc:  # pragma: no cover - install-time only
        raise EnrichmentError(
            "Le SDK anthropic n'est pas installé (pip install anthropic)."
        ) from exc

    client = Anthropic(api_key=api_key)
    user_prompt = _build_user_prompt(
        appellation=appellation,
        domaine=domaine,
        millesime=millesime,
        type_=type_,
    )
    try:
        response = client.messages.create(
            model=MODEL,
            max_tokens=600,
            system=SYSTEM_PROMPT,
            messages=[{"role": "user", "content": user_prompt}],
        )
    except Exception as exc:  # noqa: BLE001 - surface any API/network error
        log.exception("Claude call failed")
        raise EnrichmentError(f"Appel Claude échoué : {exc}") from exc

    # Claude returns a list of content blocks; we expect a single text block.
    raw = "".join(
        block.text for block in response.content if getattr(block, "type", "") == "text"
    ).strip()
    if not raw:
        raise EnrichmentError("Réponse vide de Claude.")

    # Defensive: strip a fenced code block if the model added one despite the prompt.
    if raw.startswith("```"):
        raw = raw.strip("`")
        # remove a leading "json\n" if present
        if raw.lower().startswith("json"):
            raw = raw[4:].lstrip()

    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        log.warning("Claude returned non-JSON: %r", raw[:200])
        raise EnrichmentError(f"JSON invalide : {exc}") from exc

    return WineEnrichment(
        summary=str(payload.get("summary") or "").strip(),
        apogee_year_min=_safe_year(payload.get("apogee_year_min")),
        apogee_year_max=_safe_year(payload.get("apogee_year_max")),
        keeping_year_max=_safe_year(payload.get("keeping_year_max")),
        pairings_ideal=_safe_str_list(payload.get("pairings_ideal")),
        pairings_possible=_safe_str_list(payload.get("pairings_possible")),
    )


def _safe_year(v) -> int | None:
    """Accept ints and digit strings; reject implausible years."""
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
