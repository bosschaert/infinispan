/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.distribution.rehash;

import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.context.Flag;
import org.infinispan.distribution.DistributionTestHelper;
import org.infinispan.distribution.ch.AbstractWheelConsistentHash;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.util.concurrent.IsolationLevel;
import org.infinispan.util.concurrent.TimeoutException;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.testng.annotations.Test;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import static java.lang.String.format;
import static org.infinispan.test.TestingUtil.sleepRandom;
import static org.infinispan.test.fwk.TestCacheManagerFactory.createCacheManager;

// As this is a SLOW stress test, leave it disabled by default.  Only run it manually.
@Test(groups = "stress", testName = "distribution.rehash.ConsistencyStressTest", enabled = false)
public class ConsistencyStressTest extends MultipleCacheManagersTest {
   private static final int NUM_NODES = 10;
   private static final int WORKERS_PER_NODE = 2;
   private static final int NUM_ITERATIONS = 5000;
   private static final boolean IGNORE_TX_FAILURES = true;
   private static final Log log = LogFactory.getLog(ConsistencyStressTest.class);


   @Override
   protected void createCacheManagers() throws Throwable {
      Configuration c = new Configuration();
      c.setIsolationLevel(IsolationLevel.READ_COMMITTED);
      c.setLockAcquisitionTimeout(60000);
      c.setUseLockStriping(false);
      c.setCacheMode(Configuration.CacheMode.DIST_SYNC);
      c.setL1CacheEnabled(false);
      c.setSyncReplTimeout(30000);
      c.setUseEagerLocking(true);
      c.setEagerLockSingleNode(true);
      c.setTransactionManagerLookupClass(DummyTransactionManagerLookup.class.getName());
      c.setSyncCommitPhase(true);
      c.setSyncRollbackPhase(true);

      GlobalConfiguration gc = GlobalConfiguration.getClusteredDefault();
      gc.setDistributedSyncTimeout(60000);

      List<EmbeddedCacheManager> cacheManagers = new LinkedList<EmbeddedCacheManager>();

      for (int i = 0; i < NUM_NODES; i++)
         cacheManagers.add(createCacheManager(gc, c));

      registerCacheManager(cacheManagers.toArray(new EmbeddedCacheManager[NUM_NODES]));
   }

   @SuppressWarnings("unchecked")
   public void testConsistency() throws Throwable, InterruptedException {
      // create an executor...
      ExecutorService executorService = Executors.newFixedThreadPool(NUM_NODES * WORKERS_PER_NODE, new ThreadFactory() {
         int i = 0;

         @Override
         public synchronized Thread newThread(Runnable r) {
            return new Thread(r, "Worker-" + i++);
         }
      });
      Set<Future<Void>> futures = new HashSet<Future<Void>>(NUM_NODES * WORKERS_PER_NODE);
      Set<String> keysToIgnore = new HashSet<String>();

      for (int i = 0; i < NUM_NODES; i++) {
         Cache<String, String> c = cache(i);
         for (int j = 0; j < WORKERS_PER_NODE; j++) {
            Future<Void> f = executorService.submit(new Stressor(c, i, j, keysToIgnore));
            futures.add(f);
            sleepRandom(500);
         }
      }

      // stressors are now running, generating a lot of data.
      // wait for all stressors to finish.
      log.info("Waiting for stressors to finish");
      for (Future<Void> f : futures) f.get();

      // Now shut down a node:
      TestingUtil.killCacheManagers(cacheManagers.get(0));

      // ... and ensure no data is lost.
      // Stressors encode data in the format nodeNumber|workerNumber|iterationNumber, and all have the value "value".

      Map<Address, Cache<Object, Object>> cacheMap = new HashMap<Address, Cache<Object, Object>>();
      for (int i = 1; i < NUM_NODES; i++) {
         Cache<Object, Object> c = cache(i);
         cacheMap.put(address(c), c);
      }

      // Let's enforce a quiet period to allow queued up transactions to complete.
      Thread.sleep(25000);

      // lets make sure any rehashing work has completed
      TestingUtil.blockUntilViewsReceived(60000, false, cacheMap.values());
      TestingUtil.waitForRehashToComplete(cacheMap.values());
      AbstractWheelConsistentHash hash = (AbstractWheelConsistentHash) cache(1).getAdvancedCache().getDistributionManager().getConsistentHash();

      for (int i = 0; i < NUM_NODES; i++) {
         for (int j = 0; j < WORKERS_PER_NODE; j++) {
            for (int k = 0; k < NUM_ITERATIONS; k++) {
               String key = keyFor(i, j, k);
               if (keysToIgnore.contains(key)) {
                  log.infof("Skipping test on failing key %s", key);
               } else {
                  List<Address> owners = hash.locate(key, 2);
                  for (Map.Entry<Address, Cache<Object, Object>> e : cacheMap.entrySet()) {
                     try {
                        if (owners.contains(e.getKey())) DistributionTestHelper.assertIsInContainerImmortal(e.getValue(), key);
                        // Don't bother testing non-owners since invalidations caused by rehashing are async!
                     } catch (Throwable th) {
                        log.fatalf("Key %s (hash %s) should be on owners %s according to %s", key, hash.getNormalizedHash(key), owners, hash);
                        throw th;
                     }
                  }
               }
            }
         }
      }

      executorService.shutdownNow();
   }

   private static String keyFor(int nodeId, int workerId, int iterationId) {
      return format("__%s_%s_%s__", nodeId, workerId, iterationId);
   }

   private static class Stressor implements Callable<Void> {
      private final Cache<String, String> cache;
      private final TransactionManager tm;
      private final int cacheId, workerId;
      private final Set<String> keysToIgnore;

      private Stressor(Cache<String, String> cache, int cacheId, int workerId, Set<String> keysToIgnore) {
         this.cache = cache;
         tm = TestingUtil.getTransactionManager(cache);
         this.cacheId = cacheId;
         this.workerId = workerId;
         this.keysToIgnore = keysToIgnore;
      }

      @Override
      public Void call() {
         for (int iterationId = 0; iterationId < NUM_ITERATIONS; iterationId++) {
            if (iterationId % 500 == 0)
               log.infof("  >> Stressor %s Worker %s Iteration %s", cacheId, workerId, iterationId);
            boolean txError = false;
            Exception exception = null;
            String key = keyFor(cacheId, workerId, iterationId);

            try {
               tm.begin();
               cache.getAdvancedCache().withFlags(Flag.SKIP_REMOTE_LOOKUP).put(key, "value");
               tm.commit();
            } catch (HeuristicRollbackException e) {
               txError = true;
               exception = e;
            } catch (RollbackException e) {
               txError = true;
               exception = e;
            } catch (SystemException e) {
               txError = true;
               exception = e;
            } catch (HeuristicMixedException e) {
               txError = true;
               exception = e;
            } catch (NotSupportedException e) {
               txError = true;
               exception = e;
            } catch (TimeoutException e) {
               txError = true;
               exception = e;
            }

            if (txError) {
               //first try and roll back the tx
               try {
                  tm.rollback();
               } catch (Exception exc) {
                  // rollback failed?
                  log.error("  >> Rollback failed");
               }

               if (IGNORE_TX_FAILURES) {
                  keysToIgnore.add(key);
                  log.errorf("  >> Saw a %s when trying to process key %s", exception.getClass().getSimpleName(), key);
               } else {
                  throw new RuntimeException(exception);
               }
            }
         }
         return null;
      }
   }
}
