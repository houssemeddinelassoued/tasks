/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.service;

import android.content.ContentValues;

import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.astrid.dao.MetadataDao;
import com.todoroo.astrid.dao.MetadataDao.MetadataCriteria;
import com.todoroo.astrid.data.Metadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map.Entry;

/**
 * Service layer for {@link Metadata}-centered activities.
 *
 * @author Tim Su <tim@todoroo.com>
 *
 */
public class MetadataService {

    public static interface SynchronizeMetadataCallback {
        public void beforeDeleteMetadata(Metadata m);
    }

    @Autowired
    private MetadataDao metadataDao;

    public MetadataService() {
        DependencyInjectionService.getInstance().inject(this);
    }

    // --- service layer

    /**
     * Clean up metadata. Typically called on startup
     */
    public void cleanup() {
        TodorooCursor<Metadata> cursor = metadataDao.fetchDangling(Metadata.ID);
        try {
            if(cursor.getCount() == 0) {
                return;
            }

            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                long id = cursor.getLong(0);
                metadataDao.delete(id);
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Query underlying database
     */
    public TodorooCursor<Metadata> query(Query query) {
        return metadataDao.query(query);
    }

    /**
     * Delete from metadata table where rows match a certain condition
     */
    public void deleteWhere(Criterion where) {
        metadataDao.deleteWhere(where);
    }

    /**
     * Delete from metadata table where rows match a certain condition
     * @param where predicate for which rows to update
     * @param metadata values to set
     */
    public void update(Criterion where, Metadata metadata) {
        metadataDao.update(where, metadata);
    }

    /**
     * Save a single piece of metadata
     */
    public void save(Metadata metadata) {
        if(!metadata.containsNonNullValue(Metadata.TASK)) {
            throw new IllegalArgumentException("metadata needs to be attached to a task: " + metadata.getMergedValues()); //$NON-NLS-1$
        }

        metadataDao.persist(metadata);
    }

    /**
     * Synchronize metadata for given task id
     * @return true if there were changes
     */
    public boolean synchronizeMetadata(long taskId, ArrayList<Metadata> metadata,
            Criterion metadataCriterion, SynchronizeMetadataCallback callback) {
        boolean dirty = false;
        HashSet<ContentValues> newMetadataValues = new HashSet<>();
        for(Metadata metadatum : metadata) {
            metadatum.setValue(Metadata.TASK, taskId);
            metadatum.clearValue(Metadata.CREATION_DATE);
            metadatum.clearValue(Metadata.ID);

            ContentValues values = metadatum.getMergedValues();
            for(Entry<String, Object> entry : values.valueSet()) {
                if(entry.getKey().startsWith("value")) //$NON-NLS-1$
                {
                    values.put(entry.getKey(), entry.getValue().toString());
                }
            }
            newMetadataValues.add(values);
        }

        Metadata item = new Metadata();
        TodorooCursor<Metadata> cursor = metadataDao.query(Query.select(Metadata.PROPERTIES).where(Criterion.and(MetadataCriteria.byTask(taskId),
                metadataCriterion)));
        try {
            // try to find matches within our metadata list
            for(cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                item.readFromCursor(cursor);
                long id = item.getId();

                // clear item id when matching with incoming values
                item.clearValue(Metadata.ID);
                item.clearValue(Metadata.CREATION_DATE);
                ContentValues itemMergedValues = item.getMergedValues();

                if(newMetadataValues.contains(itemMergedValues)) {
                    newMetadataValues.remove(itemMergedValues);
                    continue;
                }

                // not matched. cut it
                item.setId(id);
                if (callback != null) {
                    callback.beforeDeleteMetadata(item);
                }
                metadataDao.delete(id);
                dirty = true;
            }
        } finally {
            cursor.close();
        }

        // everything that remains shall be written
        for(ContentValues values : newMetadataValues) {
            item.clear();
            item.setValue(Metadata.CREATION_DATE, DateUtilities.now());
            item.mergeWith(values);
            metadataDao.persist(item);
            dirty = true;
        }

        return dirty;
    }
}
