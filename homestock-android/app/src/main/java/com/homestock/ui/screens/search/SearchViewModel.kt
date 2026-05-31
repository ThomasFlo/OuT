package com.homestock.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneEntity
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import com.homestock.domain.model.SearchResult
import com.homestock.voice.ParsedCommand
import com.homestock.voice.TtsManager
import com.homestock.voice.VoiceParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VoiceIntent {
    data class Search(val query: String) : VoiceIntent
    data class Add(val command: ParsedCommand) : VoiceIntent

    /** "Où est mon tournevis ?" — search + speak the location + open the fiche. */
    data class WhereIs(val query: String) : VoiceIntent
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    settingsRepository: SettingsRepository,
    private val tts: TtsManager,
) : ViewModel() {

    private val parser = VoiceParser()

    /** One-shot navigation event emitted when a voice "où est X" finds a clear winner. */
    private val _navigateToObjet = Channel<Long>(capacity = Channel.BUFFERED)
    val navigateToObjet: Flow<Long> = _navigateToObjet.receiveAsFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    val voiceLanguage: StateFlow<String> = settingsRepository.settings
        .map { it.voiceLanguage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fr-FR")

    val debugMode: StateFlow<Boolean> = settingsRepository.settings
        .map { it.debugMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val zones: StateFlow<List<ZoneEntity>> = repository.observeZones()
        .map { it.filter(ZoneEntity::actif) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recent: StateFlow<List<ObjetEntity>> = repository.observeRecent(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expiringSoon: StateFlow<List<ObjetEntity>> = repository.observeExpiringSoon(7)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Live search-as-you-type, debounced so a keystroke isn't an API call.
        observeLiveSearch()
    }

    @OptIn(FlowPreview::class)
    private fun observeLiveSearch() {
        viewModelScope.launch {
            _query
                .debounce(300)
                .distinctUntilChanged()
                .collect { value ->
                    if (value.isBlank()) {
                        _results.value = emptyList()
                    } else {
                        _searching.value = true
                        _results.value = repository.search(value)
                        _searching.value = false
                    }
                }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
        if (value.isBlank()) _results.value = emptyList()
    }

    fun search(value: String = _query.value) {
        _query.value = value
        if (value.isBlank()) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searching.value = true
            _results.value = repository.search(value)
            _searching.value = false
        }
    }

    /** Decide whether a spoken phrase is a "où est X", an "add", or a plain search. */
    fun classifyVoice(text: String): VoiceIntent {
        val lower = text.trim().lowercase()
        val isAdd = listOf("range", "ranger", "mets", "ajoute", "place").any {
            lower.startsWith(it)
        }
        if (isAdd) return VoiceIntent.Add(parser.parseAddCommand(text, zones.value))

        // "Où est X" / "où sont X" / "où se trouve X" — these are lookup
        // questions: we want a spoken answer plus the fiche. stripQuestionWords
        // (in the repository) handles the actual term cleanup before searching.
        val isWhere = lower.startsWith("où ") || lower.startsWith("ou ") ||
            lower.startsWith("où est") || lower.startsWith("ou est")
        return if (isWhere) VoiceIntent.WhereIs(text) else VoiceIntent.Search(text)
    }

    /**
     * Runs a "où est X" lookup: searches, speaks the resulting location aloud
     * and, when there is a confident match, emits a navigation event to open
     * its fiche. When no result is confident enough, speaks a fallback line
     * and leaves the list of similar items in [results].
     */
    fun whereIs(query: String) {
        _query.value = query
        viewModelScope.launch {
            _searching.value = true
            val hits = repository.search(query)
            _results.value = hits
            _searching.value = false

            val spokenQuery = stripQuestionWords(query)
            val top = hits.firstOrNull()
            val confident = top != null && top.score >= CONFIDENT_SCORE
            if (confident && top != null) {
                tts.speak(formatLocationSentence(top, spokenQuery), voiceLanguage.value)
                _navigateToObjet.trySend(top.objet.localId)
            } else if (top != null) {
                tts.speak(
                    "Je n'ai pas trouvé exactement « $spokenQuery », " +
                        "mais voici des objets similaires.",
                    voiceLanguage.value,
                )
            } else {
                tts.speak(
                    "Je n'ai trouvé aucun objet correspondant à « $spokenQuery ».",
                    voiceLanguage.value,
                )
            }
        }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)

    override fun onCleared() {
        super.onCleared()
        tts.stop()
    }

    private companion object {
        // Above this score we trust the top match enough to declare a winner
        // ("X est rangé à Y"). Below, we read the "I didn't find exactly X" line.
        const val CONFIDENT_SCORE = 0.55
    }
}

/**
 * Builds the sentence read out for a confident match, e.g.
 * "Le tournevis cruciforme est rangé à l'atelier dans le meuble miroir."
 */
private fun formatLocationSentence(result: SearchResult, query: String): String {
    val nom = result.objet.nom
    val zone = result.zoneNom?.takeIf { it.isNotBlank() }
    val emp = result.emplacementNom?.takeIf { it.isNotBlank() }
    return when {
        zone != null && emp != null -> "$nom est rangé dans $zone, dans $emp."
        zone != null -> "$nom est rangé dans $zone."
        emp != null -> "$nom est rangé dans $emp."
        else -> "J'ai trouvé $nom, mais son emplacement n'est pas renseigné."
    }
}

/**
 * Strip the same French question/article words the server-side search ranker
 * already removes, so the spoken response uses the user's actual subject —
 * "cruciforme" instead of "où est le tournevis cruciforme".
 */
private fun stripQuestionWords(query: String): String {
    val stop = setOf(
        "où", "ou", "est", "sont", "mes", "mon", "ma", "le", "la", "les",
        "un", "une", "des", "se", "trouve", "trouvent", "?", "quel", "quelle",
    )
    return query.split(" ", "?")
        .map { it.trim().trimEnd('?') }
        .filter { it.isNotBlank() && it.lowercase() !in stop }
        .joinToString(" ")
        .ifBlank { query.trim() }
}
