package com.homestock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ZoneDao {
    @Query("SELECT * FROM zones ORDER BY ordre, id")
    fun observeAll(): Flow<List<ZoneEntity>>

    @Query("SELECT * FROM zones WHERE id = :id")
    suspend fun getById(id: Long): ZoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(zones: List<ZoneEntity>)

    @Query("DELETE FROM zones WHERE id NOT IN (:ids)")
    suspend fun deleteMissing(ids: List<Long>)
}

@Dao
interface EmplacementDao {
    @Query("SELECT * FROM emplacements ORDER BY nomEmplacement")
    fun observeAll(): Flow<List<EmplacementEntity>>

    @Query("SELECT * FROM emplacements WHERE zoneId = :zoneId ORDER BY nomEmplacement")
    fun observeByZone(zoneId: Long): Flow<List<EmplacementEntity>>

    @Query("SELECT * FROM emplacements WHERE id = :id")
    suspend fun getById(id: Long): EmplacementEntity?

    @Query("SELECT COUNT(*) FROM emplacements WHERE zoneId = :zoneId")
    suspend fun countByZone(zoneId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<EmplacementEntity>)

    @Query("DELETE FROM emplacements WHERE id NOT IN (:ids)")
    suspend fun deleteMissing(ids: List<Long>)
}

@Dao
interface ObjetDao {
    @Query("SELECT * FROM objets WHERE pendingDelete = 0 ORDER BY dateModification DESC")
    fun observeAll(): Flow<List<ObjetEntity>>

    @Query(
        "SELECT * FROM objets WHERE pendingDelete = 0 ORDER BY dateAjout DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<ObjetEntity>>

    @Query(
        "SELECT o.* FROM objets o JOIN emplacements e ON o.emplacementId = e.id " +
            "WHERE e.zoneId = :zoneId AND o.pendingDelete = 0 ORDER BY o.nom"
    )
    fun observeByZone(zoneId: Long): Flow<List<ObjetEntity>>

    @Query(
        "SELECT * FROM objets WHERE categorie = :categorie AND pendingDelete = 0 " +
            "ORDER BY nom"
    )
    fun observeByCategorie(categorie: String): Flow<List<ObjetEntity>>

    @Query(
        "SELECT * FROM objets WHERE categorie = :categorie AND pendingDelete = 0 " +
            "ORDER BY vinDomaine, vinAppellation"
    )
    fun observeWines(categorie: String): Flow<List<ObjetEntity>>

    @Query(
        "SELECT * FROM objets WHERE dateExpiration IS NOT NULL " +
            "AND dateExpiration <= :before AND pendingDelete = 0 ORDER BY dateExpiration"
    )
    fun observeExpiringSoon(before: Long): Flow<List<ObjetEntity>>

    @Query("SELECT * FROM objets WHERE localId = :localId")
    suspend fun getByLocalId(localId: Long): ObjetEntity?

    @Query("SELECT * FROM objets WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: Long): ObjetEntity?

    @Query("SELECT * FROM objets WHERE pendingSync = 1 OR pendingDelete = 1")
    suspend fun getPending(): List<ObjetEntity>

    @Query("SELECT * FROM objets WHERE pendingDelete = 0")
    suspend fun getAllOnce(): List<ObjetEntity>

    @Query(
        "SELECT * FROM objets WHERE dateExpiration IS NOT NULL " +
            "AND dateExpiration <= :before AND pendingDelete = 0 ORDER BY dateExpiration"
    )
    suspend fun expiringBefore(before: Long): List<ObjetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(objet: ObjetEntity): Long

    @Update
    suspend fun update(objet: ObjetEntity)

    @Query("DELETE FROM objets WHERE localId = :localId")
    suspend fun deleteByLocalId(localId: Long)

    @Query("DELETE FROM objets WHERE serverId IS NOT NULL AND serverId NOT IN (:ids) AND pendingSync = 0")
    suspend fun deleteMissing(ids: List<Long>)
}
