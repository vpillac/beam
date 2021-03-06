/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.kinesis;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Mockito.when;

import com.amazonaws.services.kinesis.model.ExpiredIteratorException;
import java.io.IOException;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/**
 * Tests {@link ShardRecordsIterator}.
 */
@RunWith(MockitoJUnitRunner.class)
public class ShardRecordsIteratorTest {
    private static final String INITIAL_ITERATOR = "INITIAL_ITERATOR";
    private static final String SECOND_ITERATOR = "SECOND_ITERATOR";
    private static final String SECOND_REFRESHED_ITERATOR = "SECOND_REFRESHED_ITERATOR";
    private static final String THIRD_ITERATOR = "THIRD_ITERATOR";
    private static final String STREAM_NAME = "STREAM_NAME";
    private static final String SHARD_ID = "SHARD_ID";

    @Mock
    private SimplifiedKinesisClient kinesisClient;
    @Mock
    private ShardCheckpoint firstCheckpoint, aCheckpoint, bCheckpoint, cCheckpoint, dCheckpoint;
    @Mock
    private GetKinesisRecordsResult firstResult, secondResult, thirdResult;
    @Mock
    private KinesisRecord a, b, c, d;
    @Mock
    private RecordFilter recordFilter;

    private ShardRecordsIterator iterator;

    @Before
    public void setUp() throws IOException, TransientKinesisException {
        when(firstCheckpoint.getShardIterator(kinesisClient)).thenReturn(INITIAL_ITERATOR);
        when(firstCheckpoint.getStreamName()).thenReturn(STREAM_NAME);
        when(firstCheckpoint.getShardId()).thenReturn(SHARD_ID);

        when(firstCheckpoint.moveAfter(a)).thenReturn(aCheckpoint);
        when(aCheckpoint.moveAfter(b)).thenReturn(bCheckpoint);
        when(aCheckpoint.getStreamName()).thenReturn(STREAM_NAME);
        when(aCheckpoint.getShardId()).thenReturn(SHARD_ID);
        when(bCheckpoint.moveAfter(c)).thenReturn(cCheckpoint);
        when(bCheckpoint.getStreamName()).thenReturn(STREAM_NAME);
        when(bCheckpoint.getShardId()).thenReturn(SHARD_ID);
        when(cCheckpoint.moveAfter(d)).thenReturn(dCheckpoint);
        when(cCheckpoint.getStreamName()).thenReturn(STREAM_NAME);
        when(cCheckpoint.getShardId()).thenReturn(SHARD_ID);
        when(dCheckpoint.getStreamName()).thenReturn(STREAM_NAME);
        when(dCheckpoint.getShardId()).thenReturn(SHARD_ID);

        when(kinesisClient.getRecords(INITIAL_ITERATOR, STREAM_NAME, SHARD_ID))
                .thenReturn(firstResult);
        when(kinesisClient.getRecords(SECOND_ITERATOR, STREAM_NAME, SHARD_ID))
                .thenReturn(secondResult);
        when(kinesisClient.getRecords(THIRD_ITERATOR, STREAM_NAME, SHARD_ID))
                .thenReturn(thirdResult);

        when(firstResult.getNextShardIterator()).thenReturn(SECOND_ITERATOR);
        when(secondResult.getNextShardIterator()).thenReturn(THIRD_ITERATOR);
        when(thirdResult.getNextShardIterator()).thenReturn(THIRD_ITERATOR);

        when(firstResult.getRecords()).thenReturn(Collections.<KinesisRecord>emptyList());
        when(secondResult.getRecords()).thenReturn(Collections.<KinesisRecord>emptyList());
        when(thirdResult.getRecords()).thenReturn(Collections.<KinesisRecord>emptyList());

        when(recordFilter.apply(anyListOf(KinesisRecord.class), any(ShardCheckpoint
                .class))).thenAnswer(new IdentityAnswer());

        iterator = new ShardRecordsIterator(firstCheckpoint, kinesisClient, recordFilter);
    }

    @Test
    public void returnsAbsentIfNoRecordsPresent() throws IOException, TransientKinesisException {
        assertThat(iterator.next()).isEqualTo(CustomOptional.absent());
        assertThat(iterator.next()).isEqualTo(CustomOptional.absent());
        assertThat(iterator.next()).isEqualTo(CustomOptional.absent());
    }

    @Test
    public void goesThroughAvailableRecords() throws IOException, TransientKinesisException {
        when(firstResult.getRecords()).thenReturn(asList(a, b, c));
        when(secondResult.getRecords()).thenReturn(singletonList(d));

        assertThat(iterator.getCheckpoint()).isEqualTo(firstCheckpoint);
        assertThat(iterator.next()).isEqualTo(CustomOptional.of(a));
        assertThat(iterator.getCheckpoint()).isEqualTo(aCheckpoint);
        assertThat(iterator.next()).isEqualTo(CustomOptional.of(b));
        assertThat(iterator.getCheckpoint()).isEqualTo(bCheckpoint);
        assertThat(iterator.next()).isEqualTo(CustomOptional.of(c));
        assertThat(iterator.getCheckpoint()).isEqualTo(cCheckpoint);
        assertThat(iterator.next()).isEqualTo(CustomOptional.of(d));
        assertThat(iterator.getCheckpoint()).isEqualTo(dCheckpoint);
        assertThat(iterator.next()).isEqualTo(CustomOptional.absent());
        assertThat(iterator.getCheckpoint()).isEqualTo(dCheckpoint);
    }

    @Test
    public void refreshesExpiredIterator() throws IOException, TransientKinesisException {
        when(firstResult.getRecords()).thenReturn(singletonList(a));
        when(secondResult.getRecords()).thenReturn(singletonList(b));

        when(kinesisClient.getRecords(SECOND_ITERATOR, STREAM_NAME, SHARD_ID))
                .thenThrow(ExpiredIteratorException.class);
        when(aCheckpoint.getShardIterator(kinesisClient))
                .thenReturn(SECOND_REFRESHED_ITERATOR);
        when(kinesisClient.getRecords(SECOND_REFRESHED_ITERATOR, STREAM_NAME, SHARD_ID))
                .thenReturn(secondResult);

        assertThat(iterator.next()).isEqualTo(CustomOptional.of(a));
        assertThat(iterator.next()).isEqualTo(CustomOptional.of(b));
        assertThat(iterator.next()).isEqualTo(CustomOptional.absent());
    }

    private static class IdentityAnswer implements Answer<Object> {
        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            return invocation.getArguments()[0];
        }
    }
}
