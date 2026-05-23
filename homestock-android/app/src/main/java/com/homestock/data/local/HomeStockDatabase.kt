package com.homestock.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ZoneEntity::class, EmplacementEntity::class, ObjetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HomeStockDatabase : RoomDatabase() {
    abstract fun zoneDao(): ZoneDao
    abstract fun emplacementDao(): EmplacementDao
    abstract fun objetDao(): ObjetDao
}
