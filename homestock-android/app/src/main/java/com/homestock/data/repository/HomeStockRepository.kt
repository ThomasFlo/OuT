package com.homestock.data.repository

import com.homestock.data.local.EmplacementDao
import com.homestock.data.local.EmplacementEntity
import com.homestock.data.local.ObjetDao
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneDao
import com.homestock.data.local.ZoneEntity
import com.homestock.data.remote.ApiService
import com.homestock.data.remote.HostSelectionInterceptor
import com.homestock.data.remote.RealtimeClient
import com.homestock.data.remote.dto.CategoryDto
import com.homestock.data.remote.dto.CategoryRequest
import com.homestock.data.remote.dto.EmplacementRequest
import com.homestock.data.remote.dto.SearchRequest
import com.homestock.data.remote.dto.WineStats
import com.homestock.data.remote.dto.ZoneRequest
import com.homestock.domain.model.Categories
import com.homestock.domain.model.SearchResult
import com.homestock.util.normalizeForSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeStockRepository @Inject constructor(
    private val api: ApiService,
    private val zoneDao: ZoneDao,
    private val emplacementDao: EmplacementDao,
    private val objetDao: ObjetDao,
    private val realtime: RealtimeClient,
    private val host: HostSelectionInterceptor,
    private val scope: CoroutineScope,
) {
    val connected: StateFlow<Boolean> get() = realtime.connected

    private val _categories = MutableStateFlow(Categories.ALL)
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    // Richer category list (id + protected flag + object count) for the
    // management screen. Categories are not cached in Room, so this is an
    // in-memory snapshot refreshed from the server.
    private val _categoriesDetailed = MutableStateFlow<List<CategoryDto>>(emptyList())
    val categoriesDetailed: StateFlow<List<CategoryDto>> = _categoriesDetailed.asStateFlow()

    init {
        // Any server-side change triggers a local refresh.
        scope.launch {
            realtime.events.collect { runCatching { refreshAll() } }
        }
    }

    // ----- Observed flows (offline-first source of truth) -----
    fun observeZones(): Flow<List<ZoneEntity>> = zoneDao.observeAll()
    fun observeEmplacements(zoneId: Long): Flow<List<EmplacementEntity>> =
        emplacementDao.observeByZone(zoneId)
    fun observeAllEmplacements(): Flow<List<EmplacementEntity>> = emplacementDao.observeAll()
    fun observeObjetsByZone(zoneId: Long): Flow<List<ObjetEntity>> =
        objetDao.observeByZone(zoneId)
    fun observeObjetsByCategorie(categorie: String): Flow<List<ObjetEntity>> =
        objetDao.observeByCategorie(categorie)
    fun observeRecent(limit: Int = 5): Flow<List<ObjetEntity>> = objetDao.observeRecent(limit)
    fun observeExpiringSoon(withinDays: Int = 7): Flow<List<ObjetEntity>> =
        objetDao.observeExpiringSoon(System.currentTimeMillis() + withinDays * 86_400_000L)
    fun observeWines(): Flow<List<ObjetEntity>> = objetDao.observeWines(Categories.WINE)

    suspend fun expiringWithin(days: Int): List<ObjetEntity> =
        objetDao.expiringBefore(System.currentTimeMillis() + days * 86_400_000L)

    suspend fun getEmplacement(id: Long): EmplacementEntity? = emplacementDao.getById(id)
    suspend fun getZone(id: Long): ZoneEntity? = zoneDao.getById(id)
    suspend fun getObjet(localId: Long): ObjetEntity? = objetDao.getByLocalId(localId)

    // ----- Realtime control -----
    fun connectRealtime() = realtime.connect(host.baseWsUrl())
    fun disconnectRealtime() = realtime.disconnect()

    // ----- Sync -----
    suspend fun refreshAll() {
        pushPending()
        val zones = api.getZones().map { it.toEntity() }
        zoneDao.upsertAll(zones)
        // Guard: if the server returns nothing (transient error or fresh install),
        // do NOT wipe the local cache.
        if (zones.isNotEmpty()) zoneDao.deleteMissing(zones.map { it.id })

        val emplacements = api.getEmplacements().map { it.toEntity() }
        emplacementDao.upsertAll(emplacements)
        if (emplacements.isNotEmpty()) {
            emplacementDao.deleteMissing(emplacements.map { it.id })
        }

        val objets = api.getObjets()
        for (dto in objets) {
            val existing = objetDao.getByServerId(dto.id)
            objetDao.insert(dto.toEntity(existing?.localId ?: 0))
        }
        if (objets.isNotEmpty()) objetDao.deleteMissing(objets.map { it.id })

        runCatching {
            val cats = api.getCategoryList()
            _categoriesDetailed.value = cats
            _categories.value = cats.map { it.nom }
        }.onFailure {
            // Fall back to the legacy flat endpoint if the new one is missing.
            runCatching { _categories.value = api.getCategories() }
        }
    }

    /** Pull just the detailed category list (used by the management screen). */
    suspend fun refreshCategories() {
        runCatching {
            val cats = api.getCategoryList()
            _categoriesDetailed.value = cats
            _categories.value = cats.map { it.nom }
        }
    }

    suspend fun createCategory(nom: String) {
        api.createCategory(CategoryRequest(nom))
        refreshCategories()
    }

    suspend fun renameCategory(id: Long, nom: String) {
        api.updateCategory(id, CategoryRequest(nom))
        refreshAll()
    }

    suspend fun deleteCategory(id: Long) {
        api.deleteCategory(id)
        refreshCategories()
    }

    suspend fun migrateCategory(sourceId: Long, targetId: Long, deleteSource: Boolean) {
        api.migrateCategory(sourceId, targetId, deleteSource)
        refreshAll()
    }

    suspend fun reorderCategories(orderedIds: List<Long>) {
        api.reorderCategories(orderedIds)
        refreshCategories()
    }

    /** Push locally-queued changes made while offline (last-write-wins server side). */
    private suspend fun pushPending() {
        for (pending in objetDao.getPending()) {
            runCatching {
                when {
                    pending.pendingDelete && pending.serverId != null -> {
                        api.deleteObjet(pending.serverId)
                        objetDao.deleteByLocalId(pending.localId)
                    }
                    pending.pendingDelete -> objetDao.deleteByLocalId(pending.localId)
                    pending.serverId == null -> {
                        val created = api.createObjet(pending.toRequest())
                        objetDao.insert(created.toEntity(pending.localId))
                    }
                    else -> {
                        val updated = api.updateObjet(pending.serverId, pending.toRequest())
                        objetDao.insert(updated.toEntity(pending.localId))
                    }
                }
            }
        }
    }

    // ----- Objets CRUD (offline-first) -----
    suspend fun saveObjet(entity: ObjetEntity) {
        val stamped = entity.copy(dateModification = System.currentTimeMillis())
        val localId = objetDao.insert(stamped.copy(pendingSync = true))
        runCatching {
            val request = stamped.toRequest()
            val saved = if (stamped.serverId == null) {
                api.createObjet(request)
            } else {
                api.updateObjet(stamped.serverId, request)
            }
            objetDao.insert(saved.toEntity(if (localId != -1L) localId else stamped.localId))
        }.onFailure {
            // Stay queued; will be pushed on next successful sync.
        }
    }

    suspend fun deleteObjet(entity: ObjetEntity) {
        if (entity.serverId == null) {
            objetDao.deleteByLocalId(entity.localId)
            return
        }
        objetDao.update(entity.copy(pendingDelete = true))
        runCatching {
            api.deleteObjet(entity.serverId)
            objetDao.deleteByLocalId(entity.localId)
        }
    }

    // ----- Zones / Emplacements (LAN, online operations + refresh) -----
    suspend fun createZone(nom: String, icone: String, couleur: String) {
        api.createZone(ZoneRequest(nom = nom, icone = icone, couleur = couleur))
        refreshAll()
    }

    suspend fun updateZone(zone: ZoneEntity) {
        api.updateZone(
            zone.id,
            ZoneRequest(zone.nom, zone.icone, zone.couleur, zone.actif, zone.ordre),
        )
        refreshAll()
    }

    suspend fun deleteZone(id: Long) {
        api.deleteZone(id)
        refreshAll()
    }

    /**
     * Reassigns every emplacement of [sourceId] to [targetId], optionally
     * deleting the source zone once empty. Used by the UI when a non-empty
     * zone needs to be removed.
     */
    suspend fun migrateZone(sourceId: Long, targetId: Long, deleteSource: Boolean) {
        api.migrateZone(sourceId, targetId, deleteSource)
        refreshAll()
    }

    suspend fun reorderZones(orderedIds: List<Long>) {
        api.reorderZones(orderedIds)
        refreshAll()
    }

    /** Local count of emplacements belonging to a zone (for UI decisions). */
    suspend fun countEmplacements(zoneId: Long): Int = emplacementDao.countByZone(zoneId)

    suspend fun deleteEmplacement(id: Long) {
        api.deleteEmplacement(id)
        refreshAll()
    }

    suspend fun migrateEmplacement(sourceId: Long, targetId: Long, deleteSource: Boolean) {
        api.migrateEmplacement(sourceId, targetId, deleteSource)
        refreshAll()
    }

    suspend fun updateEmplacement(emp: EmplacementEntity) {
        api.updateEmplacement(
            emp.id,
            EmplacementRequest(
                zoneId = emp.zoneId,
                nomEmplacement = emp.nomEmplacement,
                description = emp.description,
                photoUrl = emp.photoUrl,
            ),
        )
        refreshAll()
    }

    /** Local count of objets in an emplacement (used to decide which dialog to show). */
    suspend fun countObjetsForEmplacement(emplacementId: Long): Int =
        objetDao.countByEmplacement(emplacementId)

    suspend fun createEmplacement(zoneId: Long, nom: String, description: String?, photoUrl: String?): EmplacementEntity {
        val dto = api.createEmplacement(
            EmplacementRequest(zoneId = zoneId, nomEmplacement = nom, description = description, photoUrl = photoUrl),
        )
        emplacementDao.upsertAll(listOf(dto.toEntity()))
        return dto.toEntity()
    }

    // ----- Search -----
    suspend fun search(query: String): List<SearchResult> {
        val clean = stripQuestionWords(query)
        return runCatching {
            api.search(SearchRequest(query = clean)).map { dto ->
                // Prefer the cached row so we have a stable localId for navigation.
                val cached = objetDao.getByServerId(dto.id)
                SearchResult(
                    objet = cached ?: ObjetEntity(
                        serverId = dto.id, nom = dto.nom, emplacementId = dto.emplacementId,
                        categorie = dto.categorie, sousCategorie = dto.sousCategorie,
                        quantite = dto.quantite, unite = dto.unite, etat = dto.etat,
                        photoUrl = dto.photoUrl, notes = dto.notes,
                    ),
                    zoneNom = dto.zoneNom,
                    emplacementNom = dto.emplacement?.nomEmplacement,
                    score = dto.score,
                    emplacementPhotoUrl = dto.emplacement?.photoUrl
                        ?: emplacementDao.getById(dto.emplacementId)?.photoUrl,
                )
            }
        }.getOrElse { localSearch(clean) }
    }

    /** Offline fallback: accent-insensitive substring match against the local cache. */
    private suspend fun localSearch(query: String): List<SearchResult> {
        val terms = normalizeForSearch(query).split(" ").filter { it.isNotBlank() }
        val results = mutableListOf<SearchResult>()
        val objets = objetDao.getAllOnce()
        for (o in objets) {
            val hay = normalizeForSearch(
                listOfNotNull(o.nom, o.sousCategorie, o.notes, o.categorie).joinToString(" "),
            )
            val matches = terms.count { hay.contains(it) }
            if (matches > 0) {
                val emp = emplacementDao.getById(o.emplacementId)
                val zone = emp?.let { zoneDao.getById(it.zoneId) }
                results += SearchResult(
                    o, zone?.nom, emp?.nomEmplacement, matches.toDouble(),
                    emplacementPhotoUrl = emp?.photoUrl,
                )
            }
        }
        return results.sortedByDescending { it.score }
    }

    // ----- Wine -----
    suspend fun wineStats(): WineStats? = runCatching { api.wineStats() }.getOrNull()

    suspend fun openBottle(serverId: Long) {
        api.openBottle(serverId)
        refreshAll()
    }

    /**
     * Triggers the server-side Claude enrichment for a wine and returns its
     * refreshed VinDto. May throw — caller handles 503 (key missing) vs 502
     * (LLM error) by inspecting the HttpException message.
     */
    suspend fun enrichWine(objetServerId: Long): com.homestock.data.remote.dto.VinDto? {
        val obj = api.enrichWine(objetServerId)
        return obj.vin
    }

    /** Fetch the latest server VinDto for one objet (used by the wine fiche). */
    suspend fun fetchVinDto(objetServerId: Long): com.homestock.data.remote.dto.VinDto? =
        runCatching { api.getObjet(objetServerId).vin }.getOrNull()

    /** Wines we should drink soon, per the server's ranking. */
    suspend fun winesPriority(): List<com.homestock.data.remote.dto.WinePriorityDto> =
        runCatching { api.winesPriority() }.getOrDefault(emptyList())

    // ----- Photos -----
    suspend fun uploadPhoto(bytes: ByteArray, fileName: String = "photo.jpg"): String? =
        runCatching {
            val body = bytes.toRequestBody("image/jpeg".toMediaType())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            host.baseHttpUrl() + api.uploadPhoto(part).photoUrl
        }.getOrNull()

    fun absolutePhotoUrl(relative: String?): String? = relative?.let {
        if (it.startsWith("http")) it else host.baseHttpUrl() + it
    }

    // ----- Connectivity -----
    suspend fun testConnection(): Boolean = runCatching { api.health(); true }.getOrDefault(false)

    suspend fun export(): Map<String, Any> = api.export()

    suspend fun importData(payload: Map<String, Any>) {
        api.importData(payload)
        refreshAll()
    }

    fun updateNas(hostName: String, port: Int) {
        host.update(hostName, port)
        connectRealtime()
    }

    fun nasAddress(): String = host.baseHttpUrl()
}

private fun stripQuestionWords(query: String): String {
    val stop = setOf(
        "où", "ou", "est", "sont", "mes", "mon", "ma", "le", "la", "les", "un", "une",
        "des", "se", "trouve", "trouvent", "?", "quel", "quelle", "range", "ranger",
    )
    return query.split(" ", "?")
        .map { it.trim().trimEnd('?') }
        .filter { it.isNotBlank() && it.lowercase() !in stop }
        .joinToString(" ")
        .ifBlank { query }
}
