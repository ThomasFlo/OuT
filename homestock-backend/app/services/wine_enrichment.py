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
import re
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

SYSTEM_PROMPT = """Sommelier français. Réponds UNIQUEMENT par un JSON valide (pas de markdown, pas de texte autour) :

{
  "summary": "2-4 phrases (caractère, robe, arômes).",
  "apogee_year_min": <année>,
  "apogee_year_max": <année>,
  "keeping_year_max": <année>,
  "pairings_ideal": ["plat 1", "plat 2", "plat 3"],
  "pairings_possible": ["plat 4", "plat 5"]
}

MÉTHODE — M = millésime (année de l'étiquette).
Calcule chaque année = M + offset selon la famille.
Si M inconnu → M = 2024.
Toujours : apogee_min ≤ apogee_max ≤ keeping_max.

OFFSETS (en années après M : début | apogée_min..max | garde_max)

Beaujolais
  Nouveau / Primeur            | M+0  | M+0..M+1  | M+2
  AOC / Villages               | M+1  | M+2..M+4  | M+6
  Cru léger (Brouilly…)        | M+2  | M+3..M+6  | M+8
  Cru classique (Fleurie…)     | M+2  | M+4..M+8  | M+10
  Cru structuré (Morgon,       | M+3  | M+5..M+10 | M+15
    Moulin-à-Vent)

Bourgogne rouge
  Régional / Hautes-Côtes      | M+2  | M+3..M+5  | M+8
  Village Côte de Beaune       | M+4  | M+6..M+10 | M+15
  Village Côte de Nuits        | M+5  | M+8..M+12 | M+18
  1er Cru                      | M+6  | M+8..M+15 | M+22
  Grand Cru                    | M+10 | M+15..M+25| M+40

Bordeaux rouge
  Bordeaux / Sup.              | M+2  | M+3..M+6  | M+8
  Côtes de Bordeaux            | M+3  | M+4..M+7  | M+10
  Médoc / Haut-Médoc           | M+3  | M+5..M+10 | M+15
  Cru Bourgeois                | M+4  | M+6..M+12 | M+18
  Saint-Émilion GC             | M+4  | M+7..M+15 | M+20
  Pomerol                      | M+5  | M+8..M+15 | M+22
  Grand Cru Classé Médoc       | M+6  | M+10..M+20| M+30
  1er GCC (Latour, Margaux…)   | M+10 | M+15..M+30| M+50

Rhône rouge
  Côtes du Rhône               | M+1  | M+2..M+4  | M+6
  Côtes du Rhône Villages      | M+2  | M+3..M+6  | M+8
  Gigondas / Vacqueyras        | M+4  | M+6..M+12 | M+18
  Châteauneuf-du-Pape          | M+5  | M+8..M+15 | M+25
  Crozes-Hermitage / St-Joseph | M+3  | M+5..M+10 | M+15
  Cornas / Côte-Rôtie          | M+5  | M+10..M+18| M+25
  Hermitage                    | M+7  | M+10..M+20| M+35

Loire rouge / Languedoc / SO
  Touraine / Anjou             | M+1  | M+2..M+4  | M+6
  Chinon / Bourgueil / Saumur  | M+2  | M+3..M+6  | M+10
  Languedoc générique          | M+1  | M+1..M+3  | M+5
  Languedoc Villages           | M+2  | M+3..M+6  | M+10
  Bandol / Cahors / Madiran    | M+3  | M+5..M+10 | M+15

Bourgogne blanc
  Régional / Mâcon             | M+1  | M+2..M+4  | M+6
  Chablis AOC                  | M+1  | M+2..M+4  | M+6
  Chablis 1er / Grand Cru      | M+2  | M+4..M+10 | M+18
  Village (Meursault, Puligny) | M+2  | M+4..M+8  | M+15
  1er Cru / Grand Cru blanc    | M+4  | M+6..M+15 | M+25

Loire blanc / Bordeaux blanc / Rhône blanc / Alsace
  Muscadet                     | M+0  | M+0..M+2  | M+4
  Sancerre / Pouilly-Fumé      | M+1  | M+2..M+4  | M+6
  Vouvray sec                  | M+2  | M+3..M+8  | M+15
  Vouvray moelleux / Layon     | M+5  | M+8..M+20 | M+40
  Bordeaux blanc sec           | M+1  | M+1..M+3  | M+5
  Pessac-Léognan blanc         | M+2  | M+3..M+6  | M+10
  Sauternes                    | M+5  | M+10..M+25| M+50
  Côtes du Rhône blanc         | M+0  | M+1..M+3  | M+5
  Châteauneuf-du-Pape blanc    | M+2  | M+3..M+8  | M+15
  Alsace courant               | M+1  | M+1..M+3  | M+5
  Riesling / Gewurz / Pinot Gr | M+2  | M+3..M+8  | M+15
  VT / SGN                     | M+5  | M+8..M+20 | M+40

Rosé / Effervescent
  Rosé courant                 | M+0  | M+0..M+1  | M+2
  Bandol / Tavel               | M+0  | M+1..M+3  | M+5
  Crémant                      | M+1  | M+2..M+4  | M+6
  Champagne BSA                | M+2  | M+3..M+5  | M+8
  Champagne millésimé          | M+5  | M+8..M+15 | M+25
  Champagne prestige           | M+8  | M+12..M+25| M+40

MODIFICATEURS
  +30 % si "Grand Cru" / "1er Cru" / "Premier Cru"
  +20 % si "Réserve" / "Cuvée prestige"
  +15 % si "Vieilles Vignes"
  Si "Nouveau" / "Primeur" → M+0 | M+0..M+1 | M+2

FALLBACK si appellation inconnue :
  Rouge → M+2 | M+3..M+6 | M+10
  Blanc → M+1 | M+1..M+3 | M+5
  Rosé  → M+0 | M+0..M+1 | M+2
Si ni appellation ni type → tout à null/[], summary = "Informations insuffisantes."

EXEMPLE — Moulin-à-Vent 2023 Rouge :
Famille = Beaujolais cru structuré. M+3 | M+5..M+10 | M+15.
2023+3=2026, 2023+5=2028, 2023+10=2033, 2023+15=2038.
{
  "summary": "Moulin-à-Vent 2023, Beaujolais cru le plus structuré : robe rubis profond, fruits noirs, violette, épices douces, trame tannique fine.",
  "apogee_year_min": 2028,
  "apogee_year_max": 2033,
  "keeping_year_max": 2038,
  "pairings_ideal": ["coq au vin", "bœuf bourguignon", "civet de lièvre"],
  "pairings_possible": ["volaille rôtie", "tomme de Savoie"]
}

RÈGLES
- Années entre 1990 et 2080.
- Si tu reconnais la famille → tu DOIS sortir les 3 années.
- Plats en français, minuscules sauf noms propres.
- JSON strict, rien d'autre.
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
    """Non-streaming variant kept for tests / fallback callers.

    Internally consumes the streaming generator and returns only the final
    parsed result; UI callers should prefer enrich_wine_stream() so the
    user sees the model typing the summary instead of staring at a frozen
    dialog for 60-90 s.
    """
    final: WineEnrichment | None = None
    async for event in enrich_wine_stream(
        appellation=appellation, domaine=domaine,
        millesime=millesime, type_=type_,
    ):
        if event.get("type") == "done":
            final = event["enrichment"]
    if final is None:
        raise EnrichmentError("Stream a terminé sans résultat.")
    return final


def _extract_partial_summary(accumulated: str) -> str | None:
    """Best-effort extraction of the 'summary' value from a partial JSON.

    Llama emits the JSON character by character. We can't parse it as a
    document until the closing brace lands, but we can recognise the
    "summary":"..." prefix and stream its growing inner value so the user
    sees the sommelier sentence being typed. Returns None until the field
    appears, then keeps returning the partial (or finished) inner text.
    """
    m = re.search(r'"summary"\s*:\s*"', accumulated)
    if not m:
        return None
    rest = accumulated[m.end():]
    out = []
    i = 0
    while i < len(rest):
        c = rest[i]
        if c == "\\" and i + 1 < len(rest):
            # JSON escape: pass the escaped char through (\n, \", etc.)
            esc = rest[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(esc, esc))
            i += 2
            continue
        if c == '"':
            return "".join(out)  # closing quote — summary complete
        out.append(c)
        i += 1
    return "".join(out)  # still streaming


async def enrich_wine_stream(
    *,
    appellation: str | None,
    domaine: str | None,
    millesime: int | None,
    type_: str | None,
):
    """Stream Llama's response, yielding progress events.

    Yields dicts with these shapes:
      {"type": "summary", "text": "Le Moulin-à-Vent..."}
        — partial summary text, sent every time it grows
      {"type": "done", "enrichment": WineEnrichment(...)}
        — terminal event with the parsed/sanity-checked result
    Exceptions propagate to the caller as before.
    """
    base_url = _ollama_url()
    if not base_url:
        raise EnrichmentDisabled(
            "OLLAMA_BASE_URL n'est pas configuré côté serveur."
        )

    model = _model_name()
    user_prompt = _build_user_prompt(
        appellation=appellation, domaine=domaine,
        millesime=millesime, type_=type_,
    )
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        # NOTE: removed `"format": "json"` here. Ollama's grammar-constrained
        # JSON decoder is known to buffer the entire response before flushing
        # the stream, which kills our token-by-token UI and burned the 180 s
        # client timeout. We now parse the model's free-form output manually
        # below (and it usually emits valid JSON anyway thanks to the prompt).
        "stream": True,
        # keep_alive: -1 = garde le modèle chargé indéfiniment en RAM. Sans
        # ça, Ollama décharge après ~5 min d'inactivité, et le call suivant
        # paie à nouveau les 30-60 s de chargement des poids sur disque.
        "keep_alive": -1,
        "options": {
            "temperature": 0.2,
            # Le prompt est ~1400 tokens, l'output ~500 ; 2048 suffit.
            # KV cache 4× plus petit qu'à 8192 → eval CPU bien plus rapide.
            "num_ctx": 2048,
            "num_predict": 600,
        },
    }

    log.info("Streaming Ollama at %s with model %s, prompt ~%d chars",
             base_url, model, len(SYSTEM_PROMPT) + len(user_prompt))

    # Tell the client we've started — the dialog can show "Connecting…" or
    # similar so the user isn't staring at a frozen Analyse… for 60 s while
    # the model is paging weights in.
    yield {"type": "started"}

    accumulated: list[str] = []
    last_summary: str | None = None
    import time as _t
    t_open = _t.monotonic()
    first_token_logged = False

    try:
        timeout = httpx.Timeout(connect=10.0, read=CALL_TIMEOUT_SECONDS,
                                write=10.0, pool=10.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream("POST", f"{base_url}/api/chat",
                                     json=payload) as resp:
                t_headers = _t.monotonic()
                log.info("Ollama responded with headers after %.1fs",
                         t_headers - t_open)
                if resp.status_code == 404:
                    raise EnrichmentError(
                        f"Modèle « {model} » indisponible sur Ollama. "
                        f"Lance « docker exec homestock-ollama ollama "
                        f"pull {model} »."
                    )
                if resp.status_code >= 400:
                    text = await resp.aread()
                    raise EnrichmentError(
                        f"Ollama a renvoyé {resp.status_code} : "
                        f"{text[:200].decode('utf-8', 'replace')}"
                    )

                async for line in resp.aiter_lines():
                    if not line.strip():
                        continue
                    try:
                        chunk = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    token = (chunk.get("message") or {}).get("content", "")
                    if token:
                        if not first_token_logged:
                            log.info(
                                "First token from Ollama after %.1fs",
                                _t.monotonic() - t_open,
                            )
                            first_token_logged = True
                        accumulated.append(token)
                        summary = _extract_partial_summary("".join(accumulated))
                        if summary is not None and summary != last_summary:
                            last_summary = summary
                            yield {"type": "summary", "text": summary}
                    if chunk.get("done"):
                        break
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
            "(le cache d'Ollama divise le temps par 5-10). Si ça persiste, "
            "ton CPU est trop juste pour ce modèle — raccourcis le prompt "
            "ou réduis num_ctx."
        ) from exc
    except httpx.HTTPError as exc:
        log.exception("Ollama call failed")
        raise EnrichmentError(f"Appel LLM échoué : {exc}") from exc

    content = "".join(accumulated).strip()
    if not content:
        raise EnrichmentError("Réponse vide d'Ollama.")

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
    if millesime and apogee_min and apogee_min > millesime + 35:
        log.warning(
            "Dropping suspiciously distant apogée_min %d for millésime %d",
            apogee_min, millesime,
        )
        apogee_min = apogee_max = keep_max = None
    elif apogee_min and apogee_max and apogee_min > apogee_max:
        log.warning("apogee_min > apogee_max (%d > %d), dropping",
                    apogee_min, apogee_max)
        apogee_min = apogee_max = None

    enrichment = WineEnrichment(
        summary=str(parsed.get("summary") or "").strip(),
        apogee_year_min=apogee_min,
        apogee_year_max=apogee_max,
        keeping_year_max=keep_max,
        pairings_ideal=_safe_str_list(parsed.get("pairings_ideal")),
        pairings_possible=_safe_str_list(parsed.get("pairings_possible")),
    )
    yield {"type": "done", "enrichment": enrichment}
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
