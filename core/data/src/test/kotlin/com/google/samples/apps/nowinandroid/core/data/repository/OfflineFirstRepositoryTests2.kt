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
import com.google.samples.apps.nowinandroid.core.network.model.NetworkChangeList
import com.google.samples.apps.nowinandroid.core.network.model.NetworkTopic
import com.google.samples.apps.nowinandroid.core.network.model.toPrintString
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
class OfflineFirstRepositoryTests2 {
    private val stringList1to19 = (1..19).toList().map(Int::toString)

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var topicsRepo: OfflineFirstTopicsRepository

    private lateinit var topicDao: TopicDao

    private lateinit var network: TestNiaNetworkDataSource

    private lateinit var niaPreferences: NiaPreferencesDataSource

    private lateinit var synchronizer: Synchronizer

    @Before
    fun setup() {
        topicDao = TestTopicDao()

        network = TestNiaNetworkDataSource()

        niaPreferences = NiaPreferencesDataSource(
            InMemoryDataStore(UserPreferences.getDefaultInstance()),
        )

        synchronizer = TestSynchronizer(niaPreferences)

        topicsRepo = OfflineFirstTopicsRepository(
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
     * 3. After synchronizing the repository with the `synchronizer`, the topicVersion is updated
     *    to match the latest version from the network (19 in this case).
     */
    @Test
    fun synchronizer_updateChangeListVersions_and_getChangeListVersions() =
        testScope.runTest {
            // Step 1: Verify the initial state of change list versions.
            assertEquals(
                expected = ChangeListVersions(topicVersion = 0, newsResourceVersion = 0),
                actual = synchronizer.getChangeListVersions(),
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

            // Step 3: Synchronize the repository with the `synchronizer`.
            // This step ensures that the repository fetches the latest change list versions
            // from the network and updates its state accordingly.
            topicsRepo.syncWith(synchronizer)

            // Verify that after synchronization, the topicVersion is updated to match
            // the latest version from the network (19)
            assertEquals(
                expected = ChangeListVersions(topicVersion = 19, newsResourceVersion = 0),
                actual = synchronizer.getChangeListVersions(),
            )
        }

    /**
     * Unit test to verify the synchronization behavior of `OfflineFirstTopicsRepository`
     * and `topicDao`.
     *
     * This test ensures that:
     * 1. The initial state of `topicDao.getTopicEntities()` is empty.
     * 2. After updating the `topicVersion` to 10 and synchronizing, the topics with IDs
     *    in the range of 11 to 19 are correctly retrieved from `topicDao`.
     * 3. After updating the `topicVersion` to 15 and synchronizing again, topics with IDs
     *    (16, 17, 18, 19, 11, 12, 13, 14, 15) are retrieved from `topicDao`
     *    TODO: Intentional behavior? Question asked on
     *     https://github.com/android/nowinandroid/issues/1796
     */
    @Test
    fun topic_entity_sync_with_version_updates() = testScope.runTest {
        // Step 1: Verify that the initial state of topicDao is empty. No topics exist initially.
        assertEquals(
            expected = emptyList<TopicEntity>(),
            actual = topicDao.getTopicEntities().first(),
        )

        // Step 2: Update the topicVersion to 10 and synchronize.
        synchronizer.updateChangeListVersions {
            copy(topicVersion = 10)
        }

        topicsRepo.syncWith(synchronizer)

        // Verify that topics with IDs from 11 to 19 are retrieved and stored in topicDao.
        assertEquals(
            expected = (11..19).toList().map(Int::toString),
            actual = topicDao.getTopicEntities().first().map(TopicEntity::id),
        )

        // Step 3: Update the topicVersion to 15 and synchronize again.
        synchronizer.updateChangeListVersions {
            copy(topicVersion = 15)
        }

        topicsRepo.syncWith(synchronizer)

        // Intentional behavior?
        assertEquals(
            expected = listOf(16, 17, 18, 19, 11, 12, 13, 14, 15).map(Int::toString),
            actual = topicDao.getTopicEntities().first().map(TopicEntity::id),
        )
    }

    /**
     * Verifies the relationship of topics data across the `network`, `topicDao`, and
     * `topicsRepo` after synchronization.
     *
     * The test ensures that:
     * 1. The `network.getTopics()`, `topicDao.getTopicEntities()`, and
     *    `topicsRepo.getTopics()` contain the same topics with IDs from 1 to 19.
     * 2. The conversion between `NetworkTopic`, `TopicEntity`, and `Topic` is accurate and
     *    consistent.
     */
    @Test
    fun network_topicDao_topicsRepo_relationship() = testScope.runTest {
        // Synchronize repository with the network
        topicsRepo.syncWith(synchronizer)

        // Fetch topics from network, DAO, and repository
        val networkTopics = network.getTopics()
        val topicDaoTopics = topicDao.getTopicEntities().first()
        val repoTopics = topicsRepo.getTopics().first()

        // Verify that all sources contain topics with IDs from 1 to 19
        assertEquals(
            expected = stringList1to19,
            actual = networkTopics.map(NetworkTopic::id),
        )

        assertEquals(
            expected = stringList1to19,
            actual = topicDaoTopics.map(TopicEntity::id),
        )

        assertEquals(
            expected = stringList1to19,
            actual = repoTopics.map(Topic::id),
        )

        // Verify that the conversion from NetworkTopic to TopicEntity is accurate
        assertEquals<List<TopicEntity>>(
            expected = topicDaoTopics,
            actual = networkTopics.map(NetworkTopic::asEntity),
        )

        // Verify that the conversion from TopicEntity to Topic is accurate
        assertEquals<List<Topic>>(
            expected = repoTopics,
            actual = topicDaoTopics.map(TopicEntity::asExternalModel),
        )

        // Verify the full conversion chain: NetworkTopic -> TopicEntity -> Topic
        assertEquals<List<Topic>>(
            expected = repoTopics,
            actual = networkTopics.map(NetworkTopic::asEntity)
                .map(TopicEntity::asExternalModel),
        )
    }

    @Test
    fun network_editCollection_affects_getTopicChangeList() = testScope.runTest {

        assertEquals(
            expected = listOf<NetworkChangeList>(
                NetworkChangeList("1", 1, false),
                NetworkChangeList("2", 2, false),
                NetworkChangeList("3", 3, false),
                NetworkChangeList("4", 4, false),
                NetworkChangeList("5", 5, false),
                NetworkChangeList("6", 6, false),
                NetworkChangeList("7", 7, false),
                NetworkChangeList("8", 8, false),
                NetworkChangeList("9", 9, false),
                NetworkChangeList("10", 10, false),
                NetworkChangeList("11", 11, false),
                NetworkChangeList("12", 12, false),
                NetworkChangeList("13", 13, false),
                NetworkChangeList("14", 14, false),
                NetworkChangeList("15", 15, false),
                NetworkChangeList("16", 16, false),
                NetworkChangeList("17", 17, false),
                NetworkChangeList("18", 18, false),
                NetworkChangeList("19", 19, false),
            ),
            actual = network.getTopicChangeList()
            // .also { println(it.toPrintString()) }
        )

        network.editCollection(
            collectionType = CollectionType.Topics,
            id = "1",
            isDelete = true,
        )

        assertEquals(
            expected = listOf<NetworkChangeList>(
                NetworkChangeList("2", 2, false),
                NetworkChangeList("3", 3, false),
                NetworkChangeList("4", 4, false),
                NetworkChangeList("5", 5, false),
                NetworkChangeList("6", 6, false),
                NetworkChangeList("7", 7, false),
                NetworkChangeList("8", 8, false),
                NetworkChangeList("9", 9, false),
                NetworkChangeList("10", 10, false),
                NetworkChangeList("11", 11, false),
                NetworkChangeList("12", 12, false),
                NetworkChangeList("13", 13, false),
                NetworkChangeList("14", 14, false),
                NetworkChangeList("15", 15, false),
                NetworkChangeList("16", 16, false),
                NetworkChangeList("17", 17, false),
                NetworkChangeList("18", 18, false),
                NetworkChangeList("19", 19, false),
                NetworkChangeList("1", 20, true),
            ),
            actual = network.getTopicChangeList()
            // .also { println(it.toPrintString()) }
        )

        // Re-add topic with id "1" and delete topics with ids "17", "18", and "19"
        network.editCollection(
            collectionType = CollectionType.Topics,
            id = "1",
            isDelete = false,
        )

        (17..19).forEach {
            network.editCollection(
                collectionType = CollectionType.Topics,
                id = it.toString(),
                isDelete = true,
            )
        }

        assertEquals(
            expected = listOf<NetworkChangeList>(
                NetworkChangeList("2", 2, false),
                NetworkChangeList("3", 3, false),
                NetworkChangeList("4", 4, false),
                NetworkChangeList("5", 5, false),
                NetworkChangeList("6", 6, false),
                NetworkChangeList("7", 7, false),
                NetworkChangeList("8", 8, false),
                NetworkChangeList("9", 9, false),
                NetworkChangeList("10", 10, false),
                NetworkChangeList("11", 11, false),
                NetworkChangeList("12", 12, false),
                NetworkChangeList("13", 13, false),
                NetworkChangeList("14", 14, false),
                NetworkChangeList("15", 15, false),
                NetworkChangeList("16", 16, false),
                NetworkChangeList("1", 21, false),
                NetworkChangeList("17", 22, true),
                NetworkChangeList("18", 23, true),
                NetworkChangeList("19", 24, true),
            ),
            actual = network.getTopicChangeList()
            // .also { println(it.toPrintString()) }
        )
    }

    /**
     * Verifies the synchronization behavior of the `topicsRepo` with the `synchronizer` and
     * `network` when topics are deleted and re-added in the collection. The test ensures that:
     *
     * 1. The `network.latestChangeListVersion` and `synchronizer.getChangeListVersions` are
     *    updated correctly.
     * 2. The `network.getTopics` reflects the correct state of topics in the network.
     * 3. The local database (`topicDao`) is updated to reflect deletions and additions accurately.
     * 4. The repository (`topicsRepo`) provides consistent data after synchronization.
     */
    @Test
    fun network_editCollection_affects_latestChangeListVersion() = testScope.runTest {
        // Initial state verification
        assertEquals(
            expected = 19,
            actual = network.latestChangeListVersion(CollectionType.Topics),
        )

        assertEquals(
            expected = 0,
            actual = synchronizer.getChangeListVersions().topicVersion,
        )

        assertEquals(
            expected = stringList1to19,
            actual = network.getTopics().map(NetworkTopic::id),
        )

        // Delete topic with id "1" from the network
        network.editCollection(
            collectionType = CollectionType.Topics,
            id = "1",
            isDelete = true,
        )

        // Verify that the change list version is updated and topic deletion is reflected
        assertEquals(
            expected = 20,
            actual = network.latestChangeListVersion(CollectionType.Topics),
        )

        assertEquals(
            expected = 0,
            actual = synchronizer.getChangeListVersions().topicVersion,
        )

        // Synchronize repository with the network
        topicsRepo.syncWith(synchronizer)

        assertEquals(
            expected = 20,
            actual = synchronizer.getChangeListVersions().topicVersion,
        )

        assertEquals(
            expected = stringList1to19,
            actual = network.getTopics().map(NetworkTopic::id),
        )

        val stringList2to19 = stringList1to19.drop(1)

        assertEquals(
            expected = stringList2to19,
            actual = topicDao.getTopicEntities().first().map(TopicEntity::id),
        )

        // Re-add topic with id "1" and delete topics with ids "17", "18", and "19"
        network.editCollection(
            collectionType = CollectionType.Topics,
            id = "1",
            isDelete = false,
        )

        (17..19).forEach {
            network.editCollection(
                collectionType = CollectionType.Topics,
                id = it.toString(),
                isDelete = true,
            )
        }

        // Synchronize repository again
        topicsRepo.syncWith(synchronizer)

        // Verify that the change list version is updated and deletions/additions are reflected correctly
        assertEquals(
            expected = 24,
            actual = network.latestChangeListVersion(CollectionType.Topics),
        )

        assertEquals(
            expected = 24,
            actual = synchronizer.getChangeListVersions().topicVersion,
        )

        assertEquals(
            expected = stringList1to19,
            actual = network.getTopics().map(NetworkTopic::id),
        )

        val stringList1to16 = stringList1to19.dropLast(3)

        assertEquals(
            expected = stringList1to16,
            actual = topicDao.getTopicEntities().first().map(TopicEntity::id),
        )

        assertEquals(
            expected = stringList1to16,
            actual = topicsRepo.getTopics().first().map(Topic::id),
        )
    }


}
