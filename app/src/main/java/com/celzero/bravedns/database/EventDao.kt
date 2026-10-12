/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for Event entities.
 * Provides methods for inserting, querying, and deleting event logs.
 */
@Dao
interface EventDao {

    /**
     * Inserts a new event into the database.
     * If a conflict occurs, the new event will replace the old one.
     *
     * @param event The event to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Event)

    /**
     * Gets the total count of events in the database.
     *
     * @return Total number of events
     */
    @Query("SELECT COUNT(*) FROM Events")
    suspend fun getCount(): Int

    /**
     * Deletes all events from the database.
     * Use with caution - this operation cannot be undone.
     */
    @Query("DELETE FROM Events")
    suspend fun deleteAll()

    // PagingSource methods for efficient pagination

}

