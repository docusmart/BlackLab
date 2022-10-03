/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.blacklab.contentstore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabIndexWriter;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.exceptions.ErrorOpeningIndex;
import nl.inl.util.UtilsForTesting;

public class TestContentStoreDirFixedBlock {

    /** How many test operations to perform */
    private static final int OPERATIONS = 500;
    
    private ContentStore store;

    private File dir;

    String[] str = { "The quick brown fox ", "jumps over the lazy ", "dog.      ", "Leentje leerde Lotje lopen lan" };

    String[] doc = new String[4];

    private boolean currentlyWriteMode;

    @Before
    public void setUp() {

        // Remove any previously left over temp test dirs
        UtilsForTesting.removeBlackLabTestDirs();

        // Create new test dir
        dir = UtilsForTesting.createBlackLabTestDir("ContentStoreDirNew");

        try {
            store = new ContentStoreFixedBlockWriter(dir, true);
            try {

                // Create four different documents that span different numbers of 4K blocks.
                Random random = new Random(12_345);
                for (int i = 0; i < doc.length; i++) {
                    StringBuilder b = new StringBuilder();
                    for (int j = 0; j < i * 2400 + 800; j++) {
                        char c = (char) ('a' + random.nextInt(26));
                        b.append(c);
                    }
                    doc[i] = b.toString();
                }

                // Store strings
                for (int i = 0; i < doc.length; i++) {
                    Assert.assertEquals(i + 1, store.store(doc[i]));
                }
            } finally {
                store.close(); // close so everything is guaranteed to be written
            }
            store = new ContentStoreFixedBlockWriter(dir, false);
            currentlyWriteMode = true;
        } catch (ErrorOpeningIndex e) {
            throw BlackLabRuntimeException.wrap(e);
        }
    }

    @After
    public void tearDown() {
        if (store != null)
            store.close();
        // Try to remove (some files may be locked though)
        UtilsForTesting.removeBlackLabTestDirs();
    }

    @Test
    public void testRetrieve() {
        ensureMode(false);
        // Retrieve strings
        for (int i = 0; i < doc.length; i++) {
            Assert.assertEquals(doc[i], store.retrieve(i + 1));
        }
    }

    @Test
    public void testRetrievePart() {
        ensureMode(false);
        String[] parts = store.retrieveParts(2, new int[] { 5, 15 }, new int[] { 7, 18 });
        Assert.assertEquals(doc[1].substring(5, 7), parts[0]);
        Assert.assertEquals(doc[1].substring(15, 18), parts[1]);
    }

    @Test
    public void testDelete() {
        store.delete(2);
        ensureMode(false);
        Assert.assertNull(store.retrieve(2));
        Assert.assertEquals(doc[0], store.retrieve(1));
    }

    @Test
    public void testDeleteReuse() {
        store.delete(2);
        store.store(doc[3]);
        ensureMode(false);
        Assert.assertEquals(doc[3], store.retrieve(5));
    }

    @Test
    public void testDeleteReuseMultiple() throws IOException {
        // Keep track of which documents are stored under which ids
        List<Integer> storedKeys = new ArrayList<>();
        Map<Integer, String> stored = new HashMap<>();
        for (int i = 1; i <= doc.length; i++) {
            storedKeys.add(i);
            stored.put(i, doc[i - 1]);
        }

        // Perform some random delete/add operations
        Random random = new Random(23_456);
        boolean testedClear = false;
        for (int i = 0; i < 500; i++) {

            if (i >= OPERATIONS / 2 && !testedClear) {
                // Halfway through the test, clear the whole content store.
                testedClear = true;
                ensureMode(true);
                store.clear();
                stored.clear();
                storedKeys.clear();
            }

            if (!stored.isEmpty() && random.nextInt(3) == 0) {
                // Choose random document. Assert it was stored correctly, then delete it.
                int keyIndex = random.nextInt(stored.size());
                Integer key = storedKeys.remove(keyIndex);
                String docContents = stored.remove(key);
                ensureMode(false);
                assertDocumentStored(random, docContents, key);
                ensureMode(true);
                store.delete(key);
                Assert.assertTrue(store.isDeleted(key));
            } else {
                // Choose random document. Insert it and assert it was stored correctly.
                int docIndex = random.nextInt(doc.length);
                String docContents = doc[docIndex];
                ensureMode(true);
                int key = store.store(docContents);
                storedKeys.add(key);
                stored.put(key, docContents);
                ensureMode(false);
                assertDocumentStored(random, docContents, key);
            }
        }

        // Check that the status of ids in the store matches those in our storedKeys list
        Set<Integer> keysFromStore = store.idSet();
        int liveDocs = 0;
        for (Integer key : keysFromStore) {
            boolean isDeleted = store.isDeleted(key);
            if (!isDeleted)
                liveDocs++;
            Assert.assertEquals(isDeleted, !storedKeys.contains(key));
        }
        Assert.assertEquals(liveDocs, storedKeys.size());

        // Finally, check that all documents are still stored correctly
        ensureMode(false);
        for (int key : storedKeys) {
            String value = stored.get(key);
            assertDocumentStored(random, value, key);
        }
    }

    private void assertDocumentStored(Random random, String docContents, int key) {
        Assert.assertFalse(store.isDeleted(key));
        Assert.assertEquals(docContents.length(), store.docLength(key));
        if (random.nextBoolean()) {
            // Retrieve full document
            Assert.assertEquals(docContents, store.retrieve(key));
        } else {
            // Retrieve part of document
            int start = random.nextInt(docContents.length() - 10);
            int end = random.nextInt(docContents.length() - start) + start;
            String docPart = docContents.substring(start, end);
            String retrievedPart = store.retrievePart(key, start, end);
            Assert.assertEquals(docPart, retrievedPart);
        }
    }
    
    public void ensureMode(boolean write) {
        if (currentlyWriteMode == write)
            return;
        try {
            store.close();
            store = ContentStore.open(dir, write, false);
            currentlyWriteMode = write;
        } catch (ErrorOpeningIndex e) {
            throw BlackLabRuntimeException.wrap(e);
        }
    }

    @Test
    public void testCloseReopen() {
        ensureMode(false);
        Assert.assertEquals(doc[0], store.retrieve(1));
    }

    @Test
    public void testCloseReopenAppend() {
        Assert.assertEquals(5, store.store("test"));
    }

    @Test
    public void testpeerIntoFile() throws ErrorOpeningIndex, IOException, InterruptedException {
        String newdir =  "/Users/estebanginez/src/blacklab-config/data/user-index/docuser_b7485ea3c98f6cb5b340f2abb697da2f/8bd8a52e-dc77-4149-a305-0aab8aadbee3_B1BC/cs_contents";
        String newIndexDir =  "/Users/estebanginez/src/blacklab-config/data/user-index/docuser_b7485ea3c98f6cb5b340f2abb697da2f/8bd8a52e-dc77-4149-a305-0aab8aadbee3_B1BC";
        String dir =  "/Users/estebanginez/src/blacklab-config/data/user-index/docuser_b7485ea3c98f6cb5b340f2abb697da2f/8bd8a52e-dc77-4149-a305-0aab8aadbee3_3902/cs_contents";
        String indexDir =  "/Users/estebanginez/src/blacklab-config/data/user-index/docuser_b7485ea3c98f6cb5b340f2abb697da2f/8bd8a52e-dc77-4149-a305-0aab8aadbee3_3902";


        countItemsInToc(newdir);
        countItemsInToc(dir);

        findDoc(indexDir, dir);
        findDoc(newIndexDir, newdir);

    }

    public void findDoc(String indexDir, String dir) throws IOException, ErrorOpeningIndex {
        System.out.println("For dir: " + indexDir);
        SimpleFSDirectory simpleDir = new SimpleFSDirectory(Paths.get(indexDir));
        ContentStoreFixedBlockReader reader = new ContentStoreFixedBlockReader(new File(dir));
        try (IndexReader freshReader = DirectoryReader.open(simpleDir )) {
            int notFoundId = 1425283;
            notFoundId = 1425017;
            Document d = freshReader.document(notFoundId);
            System.out.println("section id: " + d.getField("sectionId").stringValue());
            String contentIdStr = d.get("contents#cid");
            if (contentIdStr == null)
                throw new BlackLabRuntimeException("Lucene document has no content id: " + d);
            System.out.println(contentIdStr);
            int docId = Integer.parseInt(contentIdStr);
            ContentStoreFixedBlock.TocEntry tocEntry = reader.toc.get(docId);
            System.out.println(tocEntry);
        }
    }
    public ContentStoreFixedBlockReader countItemsInToc(String dir) throws ErrorOpeningIndex {
        System.out.println("For dir: " + dir);
        ContentStoreFixedBlockReader reader = new ContentStoreFixedBlockReader(new File(dir));
        reader.readToc();
        long deleted = 0;
        long all = 0;
        for (ContentStoreFixedBlock.TocEntry next : reader.toc) {
            if (next.deleted) {
                deleted++;
            }
            all++;
        }
        System.out.println("Deleted: " + deleted);
        System.out.println("All: " + all);
        compareToc(reader.toc);
        return reader;
    }

    public void compareToc( MutableIntObjectMap<ContentStoreFixedBlock.TocEntry> bad) {
       Map<Integer, Integer> bIdToSize = new HashMap<>();
       long deleted = 0;
       long all = 0;
       long space = 0;
       long totalBlocks = 0;
        for (ContentStoreFixedBlock.TocEntry next : bad) {
            if (next.deleted) {
                deleted += next.sizeBytes();
            }
            bIdToSize.put(next.id, next.blockIndices.length * 4 * 2);
            all += next.sizeBytes();
            space += next.blockIndices.length * 4 * 2;
            // Keep track of the number of blocks
            for (int bl : next.blockIndices) {
                if (bl > totalBlocks - 1)
                    totalBlocks = bl + 1;
            }
        }
        System.out.println("Total entries: " + bad.size());
        System.out.println("Total size of deleted entries in kb: " + deleted /(1024));
        System.out.println("Total size of all entries in mb: " + all /(1024 * 1024));
        System.out.println("Total block indices len of all entries in mb: " + space /(1024 * 1024));
        System.out.println("Total blocks: " + totalBlocks);
    }
    public void doDelete(String indexDir) throws ErrorOpeningIndex, InterruptedException {
        BlackLabIndexWriter iWriter = BlackLab.openForWriting(new File(indexDir), false);
        TermQuery q = new TermQuery(new Term("docId", "0f7c5190-43e4-4fe1-a07d-af2b72370fb0"));
        int i = 0;
        while (i < 1) {
            try {
                iWriter.delete(q);
            } catch (Exception e) {
                System.out.println("can't delete");
            } finally {
                i++;
                Thread.sleep(200);
            }
        }
    }
}
