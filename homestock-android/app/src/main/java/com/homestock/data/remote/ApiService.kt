package com.homestock.data.remote

import com.homestock.data.remote.dto.AppVersionDto
import com.homestock.data.remote.dto.CategoryDto
import com.homestock.data.remote.dto.CategoryRequest
import com.homestock.data.remote.dto.EmplacementDto
import com.homestock.data.remote.dto.EmplacementRequest
import com.homestock.data.remote.dto.ObjetDto
import com.homestock.data.remote.dto.ObjetRequest
import com.homestock.data.remote.dto.ObjetSearchResultDto
import com.homestock.data.remote.dto.PhotoUploadResponse
import com.homestock.data.remote.dto.SearchRequest
import com.homestock.data.remote.dto.WinePriorityDto
import com.homestock.data.remote.dto.WineStats
import com.homestock.data.remote.dto.ZoneDto
import com.homestock.data.remote.dto.ZoneRequest
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {
    @GET("health")
    suspend fun health(): Map<String, String>

    // Zones
    @GET("zones")
    suspend fun getZones(): List<ZoneDto>

    @POST("zones")
    suspend fun createZone(@Body body: ZoneRequest): ZoneDto

    @PUT("zones/{id}")
    suspend fun updateZone(@Path("id") id: Long, @Body body: ZoneRequest): ZoneDto

    @DELETE("zones/{id}")
    suspend fun deleteZone(@Path("id") id: Long)

    @POST("zones/{id}/migrate")
    suspend fun migrateZone(
        @Path("id") sourceId: Long,
        @Query("target_id") targetId: Long,
        @Query("delete_source") deleteSource: Boolean,
    )

    @POST("zones/reorder")
    suspend fun reorderZones(@Body orderedIds: List<Long>)

    @GET("zones/categories")
    suspend fun getCategories(): List<String>

    // Categories (editable)
    @GET("categories")
    suspend fun getCategoryList(): List<CategoryDto>

    @POST("categories")
    suspend fun createCategory(@Body body: CategoryRequest): CategoryDto

    @PUT("categories/{id}")
    suspend fun updateCategory(@Path("id") id: Long, @Body body: CategoryRequest): CategoryDto

    @DELETE("categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Long)

    @POST("categories/{id}/migrate")
    suspend fun migrateCategory(
        @Path("id") sourceId: Long,
        @Query("target_id") targetId: Long,
        @Query("delete_source") deleteSource: Boolean,
    )

    @POST("categories/reorder")
    suspend fun reorderCategories(@Body orderedIds: List<Long>)

    // Emplacements
    @GET("emplacements")
    suspend fun getEmplacements(@Query("zone_id") zoneId: Long? = null): List<EmplacementDto>

    @POST("emplacements")
    suspend fun createEmplacement(@Body body: EmplacementRequest): EmplacementDto

    @PUT("emplacements/{id}")
    suspend fun updateEmplacement(@Path("id") id: Long, @Body body: EmplacementRequest): EmplacementDto

    @DELETE("emplacements/{id}")
    suspend fun deleteEmplacement(@Path("id") id: Long)

    @POST("emplacements/{id}/migrate")
    suspend fun migrateEmplacement(
        @Path("id") sourceId: Long,
        @Query("target_id") targetId: Long,
        @Query("delete_source") deleteSource: Boolean,
    )

    // Objets
    @GET("objets")
    suspend fun getObjets(): List<ObjetDto>

    @GET("objets/{id}")
    suspend fun getObjet(@Path("id") id: Long): ObjetDto

    @POST("objets")
    suspend fun createObjet(@Body body: ObjetRequest): ObjetDto

    @PUT("objets/{id}")
    suspend fun updateObjet(@Path("id") id: Long, @Body body: ObjetRequest): ObjetDto

    @DELETE("objets/{id}")
    suspend fun deleteObjet(@Path("id") id: Long)

    // Search
    @POST("search")
    suspend fun search(@Body body: SearchRequest): List<ObjetSearchResultDto>

    // Wine
    @GET("vins/stats")
    suspend fun wineStats(): WineStats

    @POST("vins/{id}/deboucher")
    suspend fun openBottle(@Path("id") id: Long): ObjetDto

    @POST("vins/{id}/enrich")
    suspend fun enrichWine(@Path("id") id: Long): ObjetDto

    /**
     * Streaming version of [enrichWine]. Returns an NDJSON stream the caller
     * reads line by line; each line is one event:
     *   - `{"type":"summary","text":"..."}`  partial sommelier text growing
     *     character by character — display as it lands.
     *   - `{"type":"done","objet":{...}}`    final ObjetDto, persisted server-
     *     side, drop the streaming UI and render the full fiche.
     *   - `{"type":"error","message":"..."}` server-side failure, surface to
     *     the user.
     * @Streaming tells Retrofit/OkHttp to deliver the response body as it
     * arrives rather than buffering it whole — without this the stream is
     * useless because we only see it after the server is done.
     */
    @Streaming
    @POST("vins/{id}/enrich/stream")
    suspend fun enrichWineStream(@Path("id") id: Long): ResponseBody

    @GET("vins/priority")
    suspend fun winesPriority(): List<WinePriorityDto>

    // Photos
    @Multipart
    @POST("photos")
    suspend fun uploadPhoto(@Part file: MultipartBody.Part): PhotoUploadResponse

    // App self-update
    @GET("app/version")
    suspend fun appVersion(): AppVersionDto

    @Streaming
    @GET("app/download")
    suspend fun downloadApk(): ResponseBody

    // Backup
    @GET("export")
    suspend fun export(): Map<String, Any>

    @POST("import")
    suspend fun importData(@Body body: Map<String, Any>): Map<String, String>
}
