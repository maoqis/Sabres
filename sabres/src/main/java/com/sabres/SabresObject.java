/*
 * Copyright 2015 Tamir Shomer
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
 */

package com.sabres;

import android.database.Cursor;
import android.util.Log;

import com.jakewharton.fliptables.FlipTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import bolts.Continuation;
import bolts.Task;

/**
 * The SabresObject is a base class that allows custom objects to be saved and retrieved with the
 * Sabres library.
 * <p>
 * To start working with SabresObject and the Sabres library, first a model class needs to be
 * created. Getters ans Setters for custom data can also be provided.
 * <pre>
 * {@code
 * public class MyObject extends SabresObject {
 *     private static final String MY_INT_KEY = "myInt";
 *     private static final String MY_STRING_KEY = "myString";
 *
 *     public void setMyInt(int myInt) {
 *         put(MY_INT_KEY, myInt);
 *     }
 *
 *     public void setMyString(String myString) {
 *         put(MY_STRING_KEY, myString);
 *     }
 *
 *     public Integer getMyInt() {
 *         return getInt(MY_INT_KEY);
 *     }
 *
 *     public String getMyString() {
 *         return getString(MY_STRING_KEY);
 *     }
 * }
 * }
 * </pre>
 *
 * Before the object can be used, it needs to be registered with the SabresLibrary.
 * See {@link #registerSubclass(Class)}
 *
 * Now, MyObject can be created, and saved to the database.
 *
 * <pre>
 * {@code
 * MyObject myObject = new MyObject();
 * myObject.setMyInt(7);
 * myObject.setMyString("someFunString");
 * myObject.saveInBackground();
 * }
 * </pre>
 *
 * To get objects from the database, the {@link SabresQuery} object is used.
 */
abstract public class SabresObject {
    private static final String TAG = SabresObject.class.getSimpleName();
    private static final String UNDEFINED = "(undefined)";
    private static final Map<String, Class<? extends SabresObject>> subClasses = new HashMap<>();
    private static final String OBJECT_ID_KEY = "objectId";
    private static final String CREATED_AT_KEY = "createdAt";
    private static final String UPDATED_AT_KEY = "updatedAt";
    private final Map<String, SabresValue> values = new HashMap<>();
    private final Map<String, SabresDescriptor> schemaChanges = new HashMap<>();
    private final Set<String> dirtyKeys = new HashSet<>();
    private final String name;
    private boolean dataAvailable = false;
    private long id = 0;

    protected SabresObject() {
        name = getClass().getSimpleName();
    }

    private static <T extends SabresObject> T createWithoutData(Class<? extends SabresObject> clazz,
                                                                long id) {
        T object = createObjectInstance(clazz);
        object.setObjectId(id);
        return object;
    }

    static Set<String> getSubClassNames() {
        return subClasses.keySet();
    }

    static <T extends SabresObject> T createWithoutData(String className, long id) {
        return createWithoutData(subClasses.get(className), id);
    }

    /**
     * Registers a custom subclass type with Sabres library.
     * <p>
     * Needs to be called before {@link Sabres#initialize}.
     *
     * <pre>
     * {@code
     * public class MyApplication extends Application {
     *     public void onCreate() {
     *         super.onCreate();
     *         SabresObject.registerSubclass(MyObject1.class);
     *         SabresObject.registerSubclass(MyObject2.class);
     *         Sabres.initialize(this);
     *     }
     * }
     * }
     * </pre>
     *
     * @param subClass The subclass type to register.
     */
    public static void registerSubclass(Class<? extends SabresObject> subClass) {
        subClasses.put(subClass.getSimpleName(), subClass);
    }

    static String getObjectIdKey() {
        return OBJECT_ID_KEY;
    }

    private static String toString(String name, List<SabresObject> objects) {
        Map<String, SabresDescriptor> schema = Schema.getSchema(name);
        String[] headers = new String[schema.size() + 1];
        String[][] data = new String[objects.size()][schema.size() + 1];
        int i = 0;
        int j = 1;
        headers[0] = "objectId(String)";
        for (SabresObject o : objects) {
            data[i++][0] = String.valueOf(o.getObjectId());
        }

        for (Map.Entry<String, SabresDescriptor> entry : schema.entrySet()) {
            headers[j] = String.format("%s(%s)", entry.getKey(), entry.getValue().toString());
            i = 0;
            for (SabresObject o : objects) {
                data[i++][j] = o.stringify(entry.getKey());
            }
            j++;
        }
        return FlipTable.of(headers, data);
    }

    private static <T extends SabresObject> T createObjectInstance(Class<? extends SabresObject>
                                                                       clazz) {
        try {
            //noinspection unchecked
            return (T)clazz.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to instantiate class %s",
                clazz.getSimpleName()), e);
        }
    }

    /**
     * Prints all objects of a specific class
     *
     * @param clazz the class to print.
     */
    public static <T extends SabresObject> void printAll(final Class<T> clazz) {
        Task.callInBackground(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Sabres sabres = Sabres.self();
                sabres.open();
                Cursor c = null;
                try {
                    if (SqliteMaster.tableExists(sabres, clazz.getSimpleName())) {
                        c = sabres.select(new SelectCommand(clazz.getSimpleName(),
                            Schema.getKeys(clazz.getSimpleName())).toSql());
                        List<SabresObject> objects = new ArrayList<>();
                        for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
                            T object = SabresObject.createObjectInstance(clazz);
                            object.populate(sabres, c);
                            objects.add(object);
                        }

                        return SabresObject.toString(clazz.getSimpleName(), objects);
                    } else {
                        return String.format("Class %s does not exist", clazz.getSimpleName());
                    }
                } finally {
                    if (c != null) {
                        c.close();
                    }
                    sabres.close();
                }
            }
        }).continueWith(new Continuation<String, Void>() {
            @Override
            public Void then(Task<String> task) throws Exception {
                if (task.isFaulted()) {
                    Log.e(TAG, String.format("Failed to print table %s", clazz.getSimpleName()),
                        task.getError());
                } else {
                    Log.i(TAG, String.format("%s:\n%s", clazz.getSimpleName(), task.getResult()));
                }
                return null;
            }
        }, Task.UI_THREAD_EXECUTOR);
    }

    /**
     * Adds a value to a list with the given key.
     *
     * @param key   Key of list object.
     * @param value Value to add. Can be of Type Byte, Short, Integer, Long,
     *              Float, Double, Date or an extension of SabresObject.
     */
    public void add(String key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }

        put(key, Collections.singletonList(value));
    }

    /**
     * Adds objects to a list with the given key.
     *
     * @param key     Key of list object.
     * @param objects List of Objects to add. List can be of Type Byte, Short, Integer, Long,
     *                Float, Double, Date or an extension of SabresObject.
     */
    public void addAll(String key, List<?> objects) {
        put(key, objects);
    }

    /**
     * Adds a key-value pair to the object.
     *
     * @param key   Key in object.
     * @param value Can be of type Boolean, Byte, Short, Integer, Long,
     *              Float, Double, Date or an extension of SabresObject.
     */
    public void put(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }

        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }

        SabresValue sabresValue = SabresValue.create(value);

        Map<String, SabresDescriptor> schema = Schema.getSchema(name);
        if (schema != null && schema.containsKey(key)) {
            if (!schema.get(key).equals(sabresValue.getDescriptor())) {
                throw new IllegalArgumentException(String.format("Cannot set key %s to type %s. " +
                        "Already set to type %s", key, sabresValue.getDescriptor().toString(),
                    schema.get(key).toString()));
            }
        } else {
            schemaChanges.put(key, sabresValue.getDescriptor());
        }

        values.put(key, sabresValue);
        dirtyKeys.add(key);
    }

    /**
     * Gets the unique id of the object. Assigned when object is first saved into the database.
     *
     * @return objectId.
     */
    public long getObjectId() {
        return id;
    }

    void setObjectId(long objectId) {
        this.id = objectId;
    }

    /**
     * Checks if the object has been fetched from the database.
     *
     * @return true if the object has been fetched from the database. false otherwise.
     */
    public boolean isDataAvailable() {
        return dataAvailable;
    }

    private void checkDataAvailable() {
        if (id != 0 && !dataAvailable) {
            throw new IllegalStateException("No data associated with object," +
                "call fetch to populate data from database");
        }
    }

    /**
     * Checks if the object has a specific key.
     *
     * @param key The key to check.
     * @return true if the object has data paired with the given key. false otherwise.
     */
    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    /**
     * Gets a String value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a String.
     */
    public String getString(String key) {
        checkDataAvailable();
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof StringValue) {
                return ((StringValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a Boolean value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a Boolean.
     */
    public Boolean getBoolean(String key) {
        checkDataAvailable();
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof BooleanValue) {
                return ((BooleanValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets an Integer value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not an Integer.
     */
    public Integer getInt(String key) {
        checkDataAvailable();
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof IntValue) {
                return ((IntValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a Byte value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a Byte.
     */
    public Byte getByte(String key) {
        checkDataAvailable();
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof ByteValue) {
                return ((ByteValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a Short value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a Short.
     */
    public Short getShort(String key) {
        checkDataAvailable();
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof ShortValue) {
                return ((ShortValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a Long value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a Long.
     */
    public Long getLong(String key) {
        checkDataAvailable();
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof LongValue) {
                return ((LongValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a Float value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a Float.
     */
    public Float getFloat(String key) {
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof FloatValue) {
                return ((FloatValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a Double value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a Double.
     */
    public Double getDouble(String key) {
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof DoubleValue) {
                return ((DoubleValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a Date value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's not a Date.
     */
    public Date getDate(String key) {
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof DateValue) {
                return ((DateValue)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a List value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's cannot be converted to a list.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof ListValue) {
                return ((ListValue<T>)value).getValue();
            }
        }

        return null;
    }

    /**
     * Gets a custom subclass of SabresObject value for a given key.
     *
     * @param key The key to access value for.
     * @return null is there is no such value or if it's cannot be converted to a
     * SabresObject.
     */
    @SuppressWarnings("unchecked")
    public <T extends SabresObject> T getSabresObject(String key) {
        if (values.containsKey(key)) {
            SabresValue value = values.get(key);
            if (value instanceof ObjectValue) {
                return ((ObjectValue<T>)value).getValue();
            }
        }

        return null;
    }

    /**
     * Saves this object to the database in a background thread.
     * <p>
     * This is preferable to using {@link #save()} ,
     * unless your code is already running from a background thread.
     *
     * @return A Task that is resolved when the save completes.
     */
    public Task<Void> saveInBackground() {
        return Task.callInBackground(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                save();
                return null;
            }
        });
    }

    /**
     * Saves this object to the database in a background thread.
     * <p>
     * This is preferable to using {@link #save()} ,
     * unless your code is already running from a background thread.
     *
     * @param callback callback.done(e) is called when the save completes.
     */
    public void saveInBackground(final SaveCallback callback) {
        saveInBackground().continueWith(new Continuation<Void, Void>() {
            @Override
            public Void then(Task<Void> task) throws Exception {
                callback.done(SabresException.construct(task.getError()));
                return null;
            }
        });
    }

    private void saveIfNeededInTransaction(Sabres sabres) throws SabresException {
        if (id == 0 || !dirtyKeys.isEmpty()) {
            saveInTransaction(sabres);
        }
    }

    private void saveInTransaction(Sabres sabres) throws SabresException {
        put(UPDATED_AT_KEY, new Date());
        if (id == 0) {
            put(CREATED_AT_KEY, new Date());
        }

        updateSchema(sabres);
        updateChildren(sabres);

        if (id == 0) {
            id = insert(sabres);
        } else {
            update(sabres);
        }

        updateLists(sabres);

        dirtyKeys.clear();
        dataAvailable = true;
    }

    /**
     * Saves this object to the database.
     * <p>
     * Typically, you should use {@link #saveInBackground()} instead of this,
     * unless you are managing your own threading.
     *
     * @throws SabresException in case of an error with the save operation.
     */
    public void save() throws SabresException {
        final Sabres sabres = Sabres.self();
        sabres.open();
        sabres.beginTransaction();

        try {
            saveInTransaction(sabres);
            sabres.setTransactionSuccessful();
        } finally {
            sabres.endTransaction();
            sabres.close();
        }
    }

    private void updateSchema(Sabres sabres) throws SabresException {
        Map<String, SabresDescriptor> schema = Schema.getSchema(name);
        Schema.update(sabres, name, schemaChanges);
        if (schema == null) {
            createTable(sabres);
        } else {
            alterTable(sabres);
        }
    }

    private void createTable(Sabres sabres) throws SabresException {
        CreateTableCommand createCommand = new CreateTableCommand(name).ifNotExists();
        createCommand.withColumn(new Column(OBJECT_ID_KEY, SqlType.Integer).primaryKey().notNull());
        for (Map.Entry<String, SabresDescriptor> entry : schemaChanges.entrySet()) {
            Column column = new Column(entry.getKey(), entry.getValue().toSqlType());
            if (entry.getValue().getType().equals(SabresDescriptor.Type.Pointer)) {
                column.foreignKeyIn(entry.getValue().getName());
            }

            createCommand.withColumn(column);
        }

        sabres.execSQL(createCommand.toSql());
    }

    private void alterTable(Sabres sabres) throws SabresException {
        for (Map.Entry<String, SabresDescriptor> entry : schemaChanges.entrySet()) {
            sabres.execSQL(new AlterTableCommand(name, new Column(entry.getKey(),
                entry.getValue().toSqlType())).toSql());
        }
    }

    private void updateChildren(Sabres sabres) throws SabresException {
        for (Map.Entry<String, SabresValue> entry : values.entrySet()) {
            if (entry.getValue() instanceof ObjectValue) {
                SabresObject o = ((ObjectValue<?>)entry.getValue()).getValue();
                o.saveIfNeededInTransaction(sabres);
            }
        }
    }

    private void updateLists(Sabres sabres) throws SabresException {
        for (Map.Entry<String, SabresValue> entry : values.entrySet()) {
            if (entry.getValue() instanceof ListValue) {
                SabresList list = SabresList.get(sabres, name, entry.getKey());
                list.insert(sabres, id, ((ListValue<?>)entry.getValue()).getValue());
            }
        }
    }

    private long insert(Sabres sabres) throws SabresException {
        return sabres.insert(new InsertCommand(name, values).toSql());
    }

    private void update(Sabres sabres) throws SabresException {
        final Map<String, SabresValue> dirtyValues = new HashMap<>(dirtyKeys.size());
        for (String key : dirtyKeys) {
            dirtyValues.put(key, values.get(key));
        }

        UpdateCommand command = new UpdateCommand(name, dirtyValues);
        command.where(Where.equalTo(OBJECT_ID_KEY, id));
        sabres.execSQL(command.toSql());
    }

    void fetch(Sabres sabres) throws SabresException {
        Cursor c = null;
        try {
            SelectCommand command = new SelectCommand(name, Schema.getKeys(name)).
                where(Where.equalTo(OBJECT_ID_KEY, id));
            c = sabres.select(command.toSql());
            if (!c.moveToFirst()) {
                throw new SabresException(SabresException.OBJECT_NOT_FOUND,
                    String.format("table %s has no object with key %s", name, id));
            }
            populate(sabres, c);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Fetches the data of this object from the database.
     * <p>
     * Typically, you should use {@link #fetchInBackground()} instead of this,
     * unless you are managing your own threading.
     *
     * @throws SabresException in case of an error with the fetch operation.
     */
    public void fetch() throws SabresException {
        Sabres sabres = Sabres.self();
        sabres.open();
        try {
            fetch(sabres);
        } finally {
            sabres.close();
        }
    }

    /**
     * Fetches this object from the database in a background thread.
     * <p>
     * This is preferable to using {@link #fetch()},
     * unless your code is already running from a background thread.
     *
     * @return A Task that is resolved when the fetch completes.
     */
    public Task<Void> fetchInBackground() {
        return Task.callInBackground(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                fetch();
                return null;
            }
        });
    }

    /**
     * Checks if the passed SabresObject has the same Id as this.
     * @param other The other SabresObject
     * @return thue ig the id's of the objects are the same, false otherwise.
     */
    public boolean hasSameId(SabresObject other) {
        return id == other.id;
    }

    /**
     * Fetches this object from the database in a background thread.
     * <p>
     * This is preferable to using {@link #fetch()} ,
     * unless your code is already running from a background thread.
     *
     * @param callback callback.done(e) is called when the fetch completes.
     */
    public void fetchInBackground(final FetchCallback callback) {
        fetchInBackground().continueWith(new Continuation<Void, Void>() {
            @Override
            public Void then(Task<Void> task) throws Exception {
                callback.done(SabresException.construct(task.getError()));
                return null;
            }
        });
    }

    /**
     * Checks if the Object has data that has not yet been saved to the Database.
     *
     * @return true if it has data that has not been saved, false otherwise.
     */
    public boolean isDirty() {
        return !dirtyKeys.isEmpty();
    }

    /**
     * Checks if a specific key was changed and not yet saved to the database.
     *
     * @param key The key to check.
     * @return true if the value of the passed key was not saved, false otherwise.
     */
    public boolean isDirty(String key) {
        return dirtyKeys.contains(key);
    }

    /**
     * Returns a Set of all the keys in this object.
     *
     * @return a Set of all the keys in this object.
     */
    public Set<String> keySet() {
        return Schema.getSchema(name).keySet();
    }

    void populate(Sabres sabres, Cursor c) throws SabresException {
        populate(sabres, c, null);
    }

    private String getCursorKey(String prefix, String key) {
        if (prefix == null) {
            return key;
        }

        return String.format("%s_%s", prefix, key);
    }

    void populate(Sabres sabres, Cursor c, String prefix) throws SabresException {
        id = CursorHelper.getLong(c, OBJECT_ID_KEY);
        Map<String, SabresDescriptor> schema = Schema.getSchema(name);

        for (Map.Entry<String, SabresDescriptor> entry : schema.entrySet()) {
            if (!c.isNull(c.getColumnIndex(getCursorKey(prefix, entry.getKey())))) {
                SabresValue value = null;
                switch (entry.getValue().getType()) {
                    case Integer:
                        value = new IntValue(CursorHelper.getInt(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case Boolean:
                        value = new BooleanValue(CursorHelper.getBoolean(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case Byte:
                        value = new ByteValue(CursorHelper.getByte(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case Double:
                        value = new DoubleValue(CursorHelper.getDouble(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case Float:
                        value = new FloatValue(CursorHelper.getFloat(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case String:
                        value = new StringValue(CursorHelper.getString(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case Short:
                        value = new ShortValue(CursorHelper.getShort(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case Long:
                        value = new LongValue(CursorHelper.getLong(c,
                            getCursorKey(prefix, entry.getKey())));
                        break;
                    case Date:
                        value = new DateValue(CursorHelper.getDate(c, getCursorKey(prefix,
                            entry.getKey())));
                        break;
                    case Pointer:
                        SabresObject object =
                            createWithoutData(subClasses.get(entry.getValue().getName()),
                                CursorHelper.getLong(c, getCursorKey(prefix,
                                    entry.getKey())));
                        value = new ObjectValue<>(object);
                        break;
                    case List:
                        List<?> list = SabresList.get(sabres, name, entry.getKey()).
                            select(sabres, id, entry.getValue());
                        value = SabresValue.create(list);
                        break;
                }

                if (value != null) {
                    values.put(entry.getKey(), value);
                }
            }
        }

        dataAvailable = true;
    }

    void populateChild(Sabres sabres, Cursor c, String key) throws SabresException {
        SabresValue value = values.get(key);
        if (value == null) {
            throw new IllegalStateException(String.format("Child with key %s does not exist", key));
        }

        if (!(value instanceof ObjectValue)) {
            throw new IllegalArgumentException(
                String.format("value of key %s in not a SabresObject", key));
        }

        ((ObjectValue<?>)value).getValue().populate(sabres, c, key);
    }

    private String stringify(String key) {
        if (!values.containsKey(key)) {
            return UNDEFINED;
        }

        return values.get(key).toString();
    }

    @Override
    public String toString() {
        return SabresObject.toString(name, Collections.singletonList(this));
    }

    /**
     * Deletes this object from the database. This does not delete or destroy the object locally.
     *
     * @throws SabresException Throws an error if the object does not exist or if the operation
     * fails.
     */
    public void delete() throws SabresException {
        Sabres sabres = Sabres.self();
        sabres.open();
        sabres.execSQL(new DeleteCommand(name).where(Where.equalTo(OBJECT_ID_KEY, id)).toSql());
        sabres.close();
    }

    /**
     * Deletes this object on the server in a background thread.
     * <p>
     * This is preferable to using {@link #delete()}, unless your code is already running from a
     * background thread.
     *
     * @return A Task that is resolved when delete completes.
     */
    public Task<Void> deleteInBackground() {
        return Task.callInBackground(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                delete();
                return null;
            }
        });
    }

    /**
     * Deletes this object on the server in a background thread.
     * <p>
     * This is preferable to using {@link #delete()}, unless your code is already running from a
     * background thread.
     *
     * @param callback callback.done(e) is called when the delete operation completes.
     */
    public void deleteInBackground(final DeleteCallback callback) {
        deleteInBackground().continueWith(new Continuation<Void, Void>() {
            @Override
            public Void then(Task<Void> task) throws Exception {
                callback.done(SabresException.construct(task.getError()));
                return null;
            }
        });
    }
}
