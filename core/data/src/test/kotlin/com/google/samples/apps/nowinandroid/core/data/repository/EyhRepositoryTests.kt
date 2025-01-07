/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.core.data.repository

import com.google.samples.apps.nowinandroid.core.data.Synchronizer
import com.google.samples.apps.nowinandroid.core.data.model.asEntity
import com.google.samples.apps.nowinandroid.core.data.testdoubles.CollectionType
import com.google.samples.apps.nowinandroid.core.data.testdoubles.TestNiaNetworkDataSource
import com.google.samples.apps.nowinandroid.core.data.testdoubles.TestTopicDao
import com.google.samples.apps.nowinandroid.core.database.dao.TopicDao
import com.google.samples.apps.nowinandroid.core.database.model.TopicEntity
import com.google.samples.apps.nowinandroid.core.database.model.asExternalModel
import com.google.samples.apps.nowinandroid.core.datastore.ChangeListVersions
import com.google.samples.apps.nowinandroid.core.datastore.NiaPreferencesDataSource
import com.google.samples.apps.nowinandroid.core.datastore.UserPreferences
import com.google.samples.apps.nowinandroid.core.datastore.test.InMemoryDataStore
import com.google.samples.apps.nowinandroid.core.model.data.Topic
import com.google.samples.apps.nowinandroid.core.network.model.NetworkTopic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * This class contains more unit tests on the offline repositories and related classes
 */
class EyhRepositoryTests {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var subject: OfflineFirstTopicsRepository

    private lateinit var topicDao: TopicDao

    private lateinit var network: TestNiaNetworkDataSource

    private lateinit var niaPreferences: NiaPreferencesDataSource

    private lateinit var synchronizer: Synchronizer

    @Before
    fun setup() {
        topicDao = TestTopicDao()
        network = TestNiaNetworkDataSource()
        niaPreferences = NiaPreferencesDataSource(InMemoryDataStore(UserPreferences.getDefaultInstance()))
        synchronizer = TestSynchronizer(niaPreferences)

        subject = OfflineFirstTopicsRepository(
            topicDao = topicDao,
            network = network,
        )
    }

    /**
     * Unit test to verify the behavior of `synchronizer.getChangeListVersions()` and
     * `synchronizer.updateChangeListVersions()` when updating the topic version.
     *
     * This test ensures that:
     * 1. The initial state of `synchronizer.getChangeListVersions()` returns default values
     *    (topicVersion = 0, newsResourceVersion = 0).
     * 2. After updating the topicVersion to 10 using `updateChangeListVersions`, the updated
     *    version is correctly reflected in `getChangeListVersions()`.
     */
    @Test
    fun synchronizer_updateChangeListVersions_and_getChangeListVersions() =
        testScope.runTest {
            // Step 1: Verify the initial state of change list versions.
            assertEquals(
                expected = ChangeListVersions(topicVersion = 0, newsResourceVersion = 0),
                actual = synchronizer.getChangeListVersions(), // Default values should be returned.
            )

            // Step 2: Update the topicVersion to 10.
            synchronizer.updateChangeListVersions {
                copy(topicVersion = 10) // Update only the topic version.
            }

            // Verify that the updated topicVersion is reflected in change list versions,
            // while the newsResourceVersion remains unchanged at 0.
            assertEquals(
                expected = ChangeListVersions(topicVersion = 10, newsResourceVersion = 0),
                actual = synchronizer.getChangeListVersions(),
            )
        }

    /**
     * Unit test to verify the initial synchronization behavior of `subject.syncWith(synchronizer)`.
     *
     * This test ensures that:
     * 1. The initial `topicVersion` in `synchronizer.getChangeListVersions()` is 0.
     * 2. The initial state of `topicDao.getTopicEntities()` is empty.
     * 3. After invoking `subject.syncWith(synchronizer)`, the `topicDao` is populated with
     *    topics having IDs in the range of 1 to 19, indicating successful synchronization.
     */
    @Test
    fun populate_topicDao_with_topicVersion_0() =
        testScope.runTest {
            // Step 1: Verify the initial topic version in synchronizer is 0.
            assertEquals(
                expected = 0,
                actual = synchronizer.getChangeListVersions().topicVersion, // Default topicVersion should be 0.
            )

            // Step 2: Verify the initial state of topicDao is empty.
            assertEquals(
                expected = emptyList<TopicEntity>(),
                actual = topicDao.getTopicEntities().first(), // No topics should exist initially.
            )

            // Step 3: Perform synchronization and verify that topicDao is populated.
            subject.syncWith(synchronizer)

            // Verify that topicDao now contains topics with IDs from 1 to 19.
            assertEquals(
                expected = (1..19).toList().map(Int::toString),
                actual = topicDao.getTopicEntities().first().map(TopicEntity::id),
            )

            // Verify that subject.getTopics() now also contains topics with IDs from 1 to 19.
            assertEquals(
                expected = (1..19).toList().map(Int::toString),
                actual = subject.getTopics().first().map(Topic::id),
            )
        }

    /**
     * Unit test to verify the synchronization behavior of `OfflineFirstTopicsRepository` and `topicDao`.
     *
     * This test ensures that:
     * 1. The initial state of `topicDao.getTopicEntities()` is empty.
     * 2. After updating the `topicVersion` to 10 and synchronizing, the topics with IDs
     *    in the range of 11 to 19 are correctly retrieved and stored in `topicDao`.
     * 3. After updating the `topicVersion` to 15 and synchronizing again, topics with IDs
     *    in the range of 16 to 19 (newly added) are merged with previously synchronized topics
     *    (IDs 11 to 15) TODO: I'm not sure if this behavior is intentional. Question asked on
     *    https://github.com/android/nowinandroid/issues/1796
     */
    @Test
    fun topic_entity_sync_with_version_updates() =
        testScope.runTest {
            // Step 1: Verify that the initial state of topicDao is empty. No topics exist initially.
            assertEquals(
                expected = emptyList<TopicEntity>(),
                actual = topicDao.getTopicEntities().first(),
            )

            // Step 2: Update the topicVersion to 10 and synchronize.
            synchronizer.updateChangeListVersions {
                copy(topicVersion = 10)
            }
            subject.syncWith(synchronizer)

            // Verify that topics with IDs from 11 to 19 are retrieved and stored in topicDao.
            assertEquals(
                expected = (11..19).toList().map(Int::toString),
                actual = topicDao.getTopicEntities().first().map(TopicEntity::id),
            )

            // Step 3: Update the topicVersion to 15 and synchronize again.
            synchronizer.updateChangeListVersions {
                copy(topicVersion = 15)
            }
            subject.syncWith(synchronizer)

            // Verify that newly added topics (16 to 19) are merged with existing ones (11 to 15)
            assertEquals(
                expected = listOf(16, 17, 18, 19, 11, 12, 13, 14, 15).map(Int::toString),
                actual = topicDao.getTopicEntities().first().map(TopicEntity::id),
            )
        }

    @Test
    fun offlineFirstTopicsRepository_topics_stream_is_backed_by_topics_dao() =
        testScope.runTest {
            // After sync, topicDao.getTopicEntities().first() and subject.getTopics().first()
            // will return non-empty lists. 
            subject.syncWith(synchronizer)
            
            assertEquals(
                topicDao.getTopicEntities()
                    .first()
                    .map(TopicEntity::asExternalModel),
                subject.getTopics()
                    .first(),
            )
        }

    @Test
    fun offlineFirstTopicsRepository_sync_pulls_from_network() =
        testScope.runTest {
            subject.syncWith(synchronizer)

            val networkTopics = network.getTopics()
                .map(NetworkTopic::asEntity)

            val dbTopics = topicDao.getTopicEntities()
                .first()

            assertEquals(
                networkTopics.map(TopicEntity::id),
                dbTopics.map(TopicEntity::id),
            )

            // After sync version should be updated
            assertEquals(
                network.latestChangeListVersion(CollectionType.Topics),
                synchronizer.getChangeListVersions().topicVersion,
            )
        }

    @Test
    fun offlineFirstTopicsRepository_incremental_sync_pulls_from_network() =
        testScope.runTest {
            // Set topics version to 10
            synchronizer.updateChangeListVersions {
                copy(topicVersion = 10)
            }

            subject.syncWith(synchronizer)

            val networkTopics = network.getTopics()
                .map(NetworkTopic::asEntity)
                // Drop 10 to simulate the first 10 items being unchanged
                .drop(10)

            val dbTopics = topicDao.getTopicEntities()
                .first()

            assertEquals(
                networkTopics.map(TopicEntity::id),
                dbTopics.map(TopicEntity::id),
            )

            // After sync version should be updated
            assertEquals(
                network.latestChangeListVersion(CollectionType.Topics),
                synchronizer.getChangeListVersions().topicVersion,
            )
        }

    @Test
    fun offlineFirstTopicsRepository_sync_deletes_items_marked_deleted_on_network() =
        testScope.runTest {
            val networkTopics = network.getTopics()
                .map(NetworkTopic::asEntity)
                .map(TopicEntity::asExternalModel)

            // Delete half of the items on the network
            val deletedItems = networkTopics
                .map(Topic::id)
                .partition { it.chars().sum() % 2 == 0 }
                .first
                .toSet()

            deletedItems.forEach {
                network.editCollection(
                    collectionType = CollectionType.Topics,
                    id = it,
                    isDelete = true,
                )
            }

            subject.syncWith(synchronizer)

            val dbTopics = topicDao.getTopicEntities()
                .first()
                .map(TopicEntity::asExternalModel)

            // Assert that items marked deleted on the network have been deleted locally
            assertEquals(
                networkTopics.map(Topic::id) - deletedItems,
                dbTopics.map(Topic::id),
            )

            // After sync version should be updated
            assertEquals(
                network.latestChangeListVersion(CollectionType.Topics),
                synchronizer.getChangeListVersions().topicVersion,
            )
        }
}
