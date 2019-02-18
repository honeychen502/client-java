/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tikv.common;

import com.google.common.collect.Range;
import com.google.protobuf.ByteString;
import org.tikv.common.exception.TiClientInternalException;
import org.tikv.common.key.Key;
import org.tikv.common.meta.TiTimestamp;
import org.tikv.common.operation.iterator.ConcreteScanIterator;
import org.tikv.common.region.RegionStoreClient;
import org.tikv.common.region.TiRegion;
import org.tikv.common.util.BackOffer;
import org.tikv.common.util.ConcreteBackOffer;
import org.tikv.kvproto.Kvrpcpb.KvPair;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.tikv.common.util.KeyRangeUtils.makeRange;

public class Snapshot {
  private final TiTimestamp timestamp;
  //private final TiSession session;
  private final TiConfiguration conf;
  private final RegionStoreClient.RegionStoreClientBuilder clientBuilder;
  private ReadOnlyPDClient pdClient;

  public Snapshot(TiConfiguration conf, ReadOnlyPDClient pdClient,
                  RegionStoreClient.RegionStoreClientBuilder builder, TiTimestamp timestamp) {
    this.timestamp = timestamp;
    this.conf = conf;
    this.pdClient = pdClient;
    this.clientBuilder = builder;
  }

  public long getVersion() {
    return timestamp.getVersion();
  }

  public TiTimestamp getTimestamp() {
    return timestamp;
  }

  public byte[] get(byte[] key) {
    ByteString keyString = ByteString.copyFrom(key);
    ByteString value = get(keyString);
    return value.toByteArray();
  }

  public ByteString get(ByteString key) {
    RegionStoreClient client = clientBuilder.build(key);
    // TODO: Need to deal with lock error after grpc stable
    return client.get(ConcreteBackOffer.newGetBackOff(), key, timestamp.getVersion());
  }

  public Iterator<KvPair> scan(ByteString startKey) {
    BackOffer bo = ConcreteBackOffer.newTsoBackOff();
    long version = pdClient.getTimestamp(bo).getVersion();
    return new ConcreteScanIterator(conf, clientBuilder, startKey, version);
  }

  // TODO: Need faster implementation, say concurrent version
  // Assume keys sorted
  public List<KvPair> batchGet(List<ByteString> keys) {
    TiRegion curRegion = null;
    Range<Key> curKeyRange = null;
    List<ByteString> keyBuffer = new ArrayList<>();
    List<KvPair> result = new ArrayList<>(keys.size());
    BackOffer backOffer = ConcreteBackOffer.newBatchGetMaxBackOff();
    for (ByteString key : keys) {
      if (curRegion == null || !curKeyRange.contains(Key.toRawKey(key))) {
        TiRegion region = pdClient.getRegionByKey(backOffer, key);
        curRegion = region;
        curKeyRange = makeRange(curRegion.getStartKey(), curRegion.getEndKey());

        try (RegionStoreClient client = clientBuilder.build(key)) {
          List<KvPair> partialResult =
              client.batchGet(backOffer, keyBuffer, timestamp.getVersion());
          // TODO: Add lock check
          result.addAll(partialResult);
        } catch (Exception e) {
          throw new TiClientInternalException("Error Closing Store client.", e);
        }
        keyBuffer = new ArrayList<>();
        keyBuffer.add(key);
      }
    }
    return result;
  }
}
