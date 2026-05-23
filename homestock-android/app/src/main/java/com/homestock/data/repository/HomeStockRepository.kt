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
import com.homestock.data.remote.dto.EmplacementRequest
import com.homestock.data.remote.dto.SearchRequest
import com.homestock.data.remote.dto.WineStats
import com.homestock.data.remote.dto.ZoneRequest
import com.homestock.domain.model.Categories
import com.homestock.domain.model.SearchResult
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
        zoneDao.deleteMissing(zones.map { it.id }.ifEmpty { listOf(-1) })

        val emplacements = api.getEmplacements().map { it.toEntity() }
        emplacementDao.upsertAll(emplacements)
        emplacementDao.deleteMissing(emplacements.map { it.id }.ifEmpty { listOf(-1) })

        val objets = api.getObjets()
        for (dto in objets) {
            val existing = objetDao.getByServerId(dto.id)
            objetDao.insert(dto.toEntity(existing?.localId ?: 0))
        }
        objetDao.deleteMissing(objets.map { it.id }.ifEmpty { listOf(-1) })

        runCatching { _categories.value = api.getCategories() }
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
                )
            }
        }.getOrElse { localSearch(clean) }
    }

    /** Offline fallback: simple substring match against the local cache. */
    private suspend fun localSearch(query: String): List<SearchResult> {
        val terms = query.lowercase().split(" ").filter { it.isNotBlank() }
        val results = mutableListOf<SearchResult>()
        val objets = objetDao.getAllOnce()
        for (o in objets) {
            val hay = listOfNotNull(o.nom, o.sousCategorie, o.notes, o.categorie)
                .joinToString(" ").lowercase()
            val matches = terms.count { hay.contains(it) }
            if (matches > 0) {
                val emp = emplacementDao.getById(o.emplacementId)
                val zone = emp?.let { zoneDao.getById(it.zoneId) }
                results += SearchResult(o, zone?.nom, emp?.nomEmplacement, matches.toDouble())
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
