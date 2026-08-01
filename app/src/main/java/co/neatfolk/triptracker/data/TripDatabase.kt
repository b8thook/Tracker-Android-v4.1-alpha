package co.neatfolk.triptracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Trip::class, TripMetadata::class],
    version = 4,
    exportSchema = false
)
abstract class TripDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun tripMetadataDao(): TripMetadataDao

    companion object {
        @Volatile
        private var INSTANCE: TripDatabase? = null

        // v4.3-alpha: additive column only — must NOT be a destructive migration.
        // Roy has 493+ real trips logged on-device; wiping the trips table here
        // would be a real data-loss event, not just an inconvenience.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE trips ADD COLUMN pickupAutoDetected INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): TripDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TripDatabase::class.java,
                    "trip_tracker_db"
                )
                    .addMigrations(MIGRATION_3_4)
                    // Safety net only for versions with no defined migration path —
                    // the 3→4 step above always takes the additive path, never this one.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
