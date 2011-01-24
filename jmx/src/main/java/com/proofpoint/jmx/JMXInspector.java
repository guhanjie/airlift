package com.proofpoint.jmx;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.proofpoint.formatting.ColumnPrinter;
import org.weakref.jmx.Managed;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

public class JMXInspector implements Iterable<JMXInspector.InspectorRecord>
{
    private final ImmutableSortedSet<InspectorRecord> inspectorRecords;

    private static final String     CLASS_NAME_COLUMN = "NAME";
    private static final String     OBJECT_NAME_COLUMN = "METHOD/ATTRIBUTE";
    private static final String     TYPE_COLUMN = "TYPE";
    private static final String     DESCRIPTION_COLUMN = "DESCRIPTION";

    public enum Types
    {
        ATTRIBUTE,
        ACTION
    }

    public static class InspectorRecord implements Comparable<InspectorRecord>
    {
        public final String        className;
        public final String        objectName;
        public final String        description;
        public final Types         type;

        @Override
        public int hashCode()
        {
            return className.hashCode();
        }

        @Override
        public int compareTo(InspectorRecord rhs)
        {
            int     diff = objectName.compareTo(rhs.objectName);
            return (diff != 0) ? diff : className.compareTo(rhs.className);
        }

        private InspectorRecord(String className, String objectName, String description, Types type)
        {
            this.className = className;
            this.objectName = objectName;
            this.description = description;
            this.type = type;
        }
    }

    @Inject
    public JMXInspector(Injector injector) throws Exception
    {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        Set<ObjectInstance> instances = mBeanServer.queryMBeans(null, null);
        Multimap<String, String> nameMap = ArrayListMultimap.create();
        for ( ObjectInstance i : instances )
        {
            nameMap.put(i.getClassName(), i.getObjectName().getCanonicalName());
        }

        ImmutableSortedSet.Builder<InspectorRecord>    builder = ImmutableSortedSet.naturalOrder();
        GuiceInjectorIterator injectorIterator = new GuiceInjectorIterator(injector);
        for ( Class<?> clazz : injectorIterator )
        {
            addConfig(nameMap, clazz, builder);
        }

        inspectorRecords = builder.build();
    }

    @Override
    public Iterator<InspectorRecord> iterator()
    {
        return Iterators.unmodifiableIterator(inspectorRecords.iterator());
    }

    /**
     * Print the details to the given stream
     *
     * @param out stream
     */
    public void     print(PrintWriter out)
    {
        ColumnPrinter columnPrinter = makePrinter();
        columnPrinter.print(out);
        out.flush();
    }

    private ColumnPrinter makePrinter()
    {
        ColumnPrinter       columnPrinter = new ColumnPrinter();

        columnPrinter.addColumn(CLASS_NAME_COLUMN);
        columnPrinter.addColumn(OBJECT_NAME_COLUMN);
        columnPrinter.addColumn(TYPE_COLUMN);
        columnPrinter.addColumn(DESCRIPTION_COLUMN);

        for ( InspectorRecord record : inspectorRecords )
        {
            columnPrinter.addValue(CLASS_NAME_COLUMN, record.className);
            columnPrinter.addValue(OBJECT_NAME_COLUMN, record.objectName);
            columnPrinter.addValue(TYPE_COLUMN, record.type.name().toLowerCase());
            columnPrinter.addValue(DESCRIPTION_COLUMN, record.description);
        }
        return columnPrinter;
    }

    private void addConfig(Multimap<String, String> nameMap, Class clazz, ImmutableSortedSet.Builder<InspectorRecord> builder) throws InvocationTargetException, IllegalAccessException
    {
        Collection<String> thisNameList = nameMap.get(clazz.getName());
        if ( thisNameList != null )
        {
            for ( Method method : clazz.getMethods() )
            {
                Managed configAnnotation = method.getAnnotation(Managed.class);
                if ( configAnnotation != null )
                {
                    for ( String thisName : thisNameList )
                    {
                        builder.add(new InspectorRecord(thisName, method.getName(), configAnnotation.description(), getType(method)));
                    }
                }
            }
        }
    }

    private Types getType(Method method)
    {
        if ( method.getReturnType() == Void.TYPE )
        {
            return Types.ACTION;
        }

        return (method.getParameterTypes().length > 0) ? Types.ACTION : Types.ATTRIBUTE;
    }
}
