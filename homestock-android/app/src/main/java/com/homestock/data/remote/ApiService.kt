package com.homestock.data.remote

import com.homestock.data.remote.dto.EmplacementDto
import com.homestock.data.remote.dto.EmplacementRequest
import com.homestock.data.remote.dto.ObjetDto
import com.homestock.data.remote.dto.ObjetRequest
import com.homestock.data.remote.dto.ObjetSearchResultDto
import com.homestock.data.remote.dto.PhotoUploadResponse
import com.homestock.data.remote.dto.SearchRequest
import com.homestock.data.remote.dto.WineStats
import com.homestock.data.remote.dto.ZoneDto
import com.homestock.data.remote.dto.ZoneRequest
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

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

    @GET("zones/categories")
    suspend fun getCategories(): List<String>

    // Emplacements
    @GET("emplacements")
    suspend fun getEmplacements(@Query("zone_id") zoneId: Long? = null): List<EmplacementDto>

    @POST("emplacements")
    suspend fun createEmplacement(@Body body: EmplacementRequest): EmplacementDto

    @PUT("emplacements/{id}")
    suspend fun updateEmplacement(@Path("id") id: Long, @Body body: EmplacementRequest): EmplacementDto

    @DELETE("emplacements/{id}")
    suspend fun deleteEmplacement(@Path("id") id: Long)

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

    // Photos
    @Multipart
    @POST("photos")
    suspend fun uploadPhoto(@Part file: MultipartBody.Part): PhotoUploadResponse

    // Backup
    @GET("export")
    suspend fun export(): Map<String, Any>

    @POST("import")
    suspend fun importData(@Body body: Map<String, Any>): Map<String, String>
}
