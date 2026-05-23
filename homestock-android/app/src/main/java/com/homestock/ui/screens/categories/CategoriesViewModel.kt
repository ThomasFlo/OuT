package com.homestock.ui.screens.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.ObjetEntity
import com.homestock.data.repository.HomeStockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.net.URLDecoder
import javax.inject.Inject

data class CategoryObjet(
    val objet: ObjetEntity,
    val zoneNom: String?,
    val emplacementNom: String?,
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    repository: HomeStockRepository,
) : ViewModel() {
    val categories: StateFlow<List<String>> = repository.categories
}

@HiltViewModel
class CategoryDetailViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val categorie: String = savedStateHandle.get<String>("categorie")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?: "Autre"

    val objets: StateFlow<List<CategoryObjet>> = combine(
        repository.observeObjetsByCategorie(categorie),
        repository.observeAllEmplacements(),
        repository.observeZones(),
    ) { objets, emplacements, zones ->
        val empById = emplacements.associateBy { it.id }
        val zoneById = zones.associateBy { it.id }
        objets.map { o ->
            val emp = empById[o.emplacementId]
            CategoryObjet(o, emp?.let { zoneById[it.zoneId]?.nom }, emp?.nomEmplacement)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)
}
