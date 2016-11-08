/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package dap4.dap4lib.netcdf;

import com.sun.jna.Pointer;
import dap4.core.data.DataCursor;
import dap4.core.dmr.*;
import dap4.core.util.*;
import dap4.dap4lib.AbstractCursor;
import dap4.dap4lib.LibTypeFcns;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static dap4.dap4lib.netcdf.Nc4DSP.Nc4Pointer;
import static dap4.dap4lib.netcdf.Nc4Notes.*;


public class Nc4Cursor extends AbstractCursor
{

    //////////////////////////////////////////////////

    static public boolean DEBUG = true;

    //////////////////////////////////////////////////
    // Instance variables

    protected Nc4Pointer memory = null;

    //////////////////////////////////////////////////
    // Constructor(s)

    public Nc4Cursor(Scheme scheme, Nc4DSP dsp, DapVariable template, Nc4Cursor container)
            throws DapException
    {
        super(scheme, dsp, template, container);
        if(DEBUG) debug();
    }

    //////////////////////////////////////////////////
    // AbstractCursor Interface API Implementations

    @Override
    public Object
    read(Index index)
            throws DapException
    {
        return read(DapUtil.indexToSlices(index));
    }

    @Override
    public Object
    read(List<Slice> slices)
            throws DapException
    {
        switch (this.scheme) {
        case ATOMIC:
            return readAtomic(slices);
        case STRUCTARRAY:
            Odometer odom = Odometer.factory(slices);
            DataCursor[] instances = new DataCursor[(int) odom.totalSize()];
            for(int i = 0; odom.hasNext(); i++) {
                instances[i] = readStructure(odom.next());
            }
            return instances;
        case SEQARRAY:
            odom = Odometer.factory(slices);
            instances = new DataCursor[(int) odom.totalSize()];
            for(int i = 0; odom.hasNext(); i++) {
                instances[i] = readSequence(odom.next());
            }
            return instances;
        default:
            throw new DapException("Attempt to slice a scalar object");
        }
    }

    @Override
    public Object
    readField(int findex, Index index)
            throws DapException
    {
        return readField(findex, DapUtil.indexToSlices(index));
    }

    @Override
    public Object
    readField(int findex, List<Slice> slices)
            throws DapException
    {
        assert (this.scheme == scheme.RECORD || this.scheme == scheme.STRUCTURE);
        DapVariable template = (DapVariable) getTemplate();
        DapStructure struct = (DapStructure) template.getBaseType();
        if(findex < 0 || findex >= struct.getFields().size())
            throw new DapException("Field index out of range: " + findex);
        DapVariable field = struct.getField(findex);
        // Get VarNotes and TypeNotes
        VarNotes fi = (VarNotes) field.annotation();
        long dimproduct = DapUtil.dimProduct(template.getDimensions());
        TypeNotes ti = fi.getBaseType();
        long elemsize = getElementSize(ti); // read only one instance
        long totalsize = elemsize * dimproduct;
        Nc4Cursor cursor = null;
        TypeSort typesort = ti.getType().getTypeSort();
        if(typesort.isAtomic()) {
            cursor = new Nc4Cursor(Scheme.ATOMIC, (Nc4DSP) this.dsp, field, this);
        } else if(typesort == TypeSort.Structure) {
            cursor = new Nc4Cursor(Scheme.STRUCTARRAY, (Nc4DSP) this.dsp, field, this);
        } else if(typesort == TypeSort.Sequence)
            throw new UnsupportedOperationException();
        // as a rule, a field's memory is its parent container memory.
        return cursor;
    }

    @Override
    public long
    getRecordCount()
    {
        assert (this.scheme == scheme.SEQUENCE);
        throw new UnsupportedOperationException("Not a Sequence");
    }

    @Override
    public DataCursor
    getRecord(long i)
    {
        assert (this.scheme == scheme.SEQUENCE);
        throw new UnsupportedOperationException("Not a Sequence");
    }


    @Override
    public Index
    getIndex()
            throws DapException
    {
        if(this.scheme != Scheme.STRUCTURE && this.scheme != Scheme.SEQUENCE)
            throw new DapException("Not a Sequence|Structure instance");
        return this.index;
    }

    //////////////////////////////////////////////////
    // Support Methods

    protected Object
    readAtomic(List<Slice> slices)
            throws DapException
    {
        if(slices == null)
            throw new DapException("DataCursor.read: null set of slices");
        assert (this.scheme == scheme.ATOMIC);
        DapVariable atomvar = (DapVariable) getTemplate();
        int rank = atomvar.getRank();
        assert slices != null && slices.size() == rank;
        // Get VarNotes and TypeNotes
        Notes n = (Notes) this.template.annotation();
        Object result = null;
        long count = DapUtil.sliceProduct(slices);
        if(getContainer() == null) {
            VarNotes vi = (VarNotes) n;
            TypeNotes ti = vi.basetype;
            if(rank == 0) { //scalar
                result = readAtomicScalar(vi, ti);
            } else {
                result = readAtomicVector(vi, ti, count, slices);
            }
        } else {// field of a structure instance or record
            VarNotes fn = (VarNotes) n;
            TypeNotes ti = fn.getBaseType();
            long elemsize = ((DapType) ti.get()).getSize();
            assert (this.container != null);
            long trueoffset = computeTrueOffset(this);
            Nc4Pointer basemem = getVarMemory(this);
            Nc4Pointer mem = basemem.share(trueoffset, count * elemsize);
            setMemory(mem);
            result = getatomicdata(ti.getType(), count, elemsize, mem);
        }
        return result;
    }

    /**
     * Read a top-level scalar atomic variable
     *
     * @param vi
     * @param ti
     * @return
     * @throws DapException
     */
    protected Object
    readAtomicScalar(VarNotes vi, TypeNotes ti)
            throws DapException
    {
        DapVariable atomvar = (DapVariable) getTemplate();
        // Get into memory
        DapNetcdf nc4 = ((Nc4DSP) this.dsp).getJNI();
        int ret;
        DapType basetype = ti.getType();
        Object result = null;
        if(basetype.isFixedSize()) {
            long memsize = ((DapType) ti.get()).getSize();
            Nc4Pointer mem = Nc4Pointer.allocate(memsize);
            readcheck(nc4, ret = nc4.nc_get_var(vi.gid, vi.id, mem.p));
            setMemory(mem);
            result = getatomicdata(ti.getType(), 1, mem.size, mem);
        } else if(basetype.isStringType()) {
            String[] s = new String[1];
            readcheck(nc4, ret = nc4.nc_get_var_string(vi.gid, vi.id, s));
            result = s;
        } else if(basetype.isOpaqueType()) {
            Nc4Pointer mem = Nc4Pointer.allocate(ti.getSize());
            readcheck(nc4, ret = nc4.nc_get_var(vi.gid, vi.id, mem.p));
            setMemory(mem);
            ByteBuffer[] buf = new ByteBuffer[1];
            buf[0] = mem.p.getByteBuffer(0, ti.getSize());
            result = buf;
        } else
            throw new DapException("Unexpected atomic type: " + basetype);
        return result;
    }

    protected Object
    readAtomicVector(VarNotes vi, TypeNotes ti, long count, List<Slice> slices)
            throws DapException
    {
        DapVariable atomvar = (DapVariable) getTemplate();
        // Get into memory
        DapNetcdf nc4 = ((Nc4DSP) this.dsp).getJNI();
        DapType basetype = ti.getType();
        if(atomvar.getCount() == 0)
            return LibTypeFcns.newVector(basetype, 0);
        // Convert slices to (start,count,stride);
        int rank = atomvar.getRank();
        SizeT[] startp = new SizeT[rank];
        SizeT[] countp = new SizeT[rank];
        SizeT[] stridep = new SizeT[rank];
        slicesToVars(slices, startp, countp, stridep);
        int ret;
        Object result = null;
        if(basetype.isFixedSize()) {
            Odometer odom = Odometer.factory(slices);
            long elemsize = ((DapType) ti.get()).getSize();
            long memsize = count * elemsize;
            Nc4Pointer mem = Nc4Pointer.allocate(memsize);
            readcheck(nc4, ret = nc4.nc_get_vars(vi.gid, vi.id, startp, countp, stridep, mem.p));
            result = getatomicdata(ti.getType(), count, elemsize, mem);
        } else if(basetype.isStringType()) {
            String[] ss = new String[(int) count];
            readcheck(nc4, ret = nc4.nc_get_vars_string(vi.gid, vi.id, startp, countp, stridep, ss));
            result = ss;
        } else if(basetype.isOpaqueType()) {
            Nc4Pointer mem = Nc4Pointer.allocate(count * ti.getSize());
            readcheck(nc4, ret = nc4.nc_get_var(vi.gid, vi.id, mem.p));
            ByteBuffer[] buf = new ByteBuffer[(int) count];
            for(int i = 0; i < count; i++) {
                buf[i] = mem.p.getByteBuffer(ti.getSize() * i, ti.getSize());
            }
            result = buf;
        } else
            throw new DapException("Unexpected atomic type: " + basetype);
        return result;
    }

    protected Nc4Cursor
    getStructure(Index index)
            throws DapException
    {
        assert (index != null);
        assert this.scheme == Scheme.STRUCTARRAY;
        DapVariable template = (DapVariable) getTemplate();
        VarNotes vi = (VarNotes) template.annotation();
        TypeNotes ti = vi.basetype;
        Nc4Pointer mem;
        Nc4Cursor cursor = null;
        if(template.isTopLevel()) {
            int ret;
            mem = Nc4Pointer.allocate(ti.getSize());
            DapNetcdf nc4 = ((Nc4DSP) this.dsp).getJNI();
            if(index.getRank() == 0) {
                readcheck(nc4, ret = nc4.nc_get_var(vi.gid, vi.id, mem.p));
            } else {
                SizeT[] sizes = indexToSizes(index);
                readcheck(nc4, ret = nc4.nc_get_var1(vi.gid, vi.id, sizes, mem.p));
            }
            cursor = new Nc4Cursor(Scheme.STRUCTURE, (Nc4DSP) this.dsp, template, this);
        } else {// field of a structure instance or record
            long pos = index.index();
            if(pos < 0 || pos >= template.getCount())
                throw new IndexOutOfBoundsException("read: " + index);
            cursor = new Nc4Cursor(Scheme.STRUCTURE, (Nc4DSP) this.dsp, template, this);
            // Ok, we need to operate relative to the parent's memory
            // move to the appropriate offset
            mem = ((Nc4Cursor) getContainer()).getMemory().share(pos * ti.getSize(), ti.getSize());
        }
        cursor.setIndex(index);
        cursor.setMemory(mem);
        return cursor;
    }

    protected Nc4Cursor
    getSequence(Index index)
            throws DapException
    {
        throw new UnsupportedOperationException();
    }

    //////////////////////////////////////////////////
    // Nc4Cursor Extensions

    public long
    getOffset()
    {
        DapVariable dv = (DapVariable) getTemplate();
        Notes n = (Notes) dv.annotation();
        return n.getOffset();
    }

    public long
    getElementSize()
    {
        DapVariable dv = (DapVariable) getTemplate();
        Notes n = (Notes) dv.annotation();
        return n.getSize();
    }

    public Nc4Pointer
    getMemory()
    {
        return this.memory;
    }

    public Nc4Cursor
    setMemory(Nc4Pointer p)
    {
        this.memory = p;
        return this;
    }

    //////////////////////////////////////////////////
    // Type Decls

    // com.sun.jna.Memory control
    /*
    static package abstract class Nc4Pointer
    {
        static Memory
        allocate(long size)
        {
            if(size == 0)
                throw new IllegalArgumentException("Attempt to allocate zero bytes");
            Memory m = new Memory(size);
            return m;
        }

    }  */

    //////////////////////////////////////////////////
    // Utilities

    static protected long
    getElementSize(TypeNotes ti)
    {
        DapType type = ti.getType();
        switch (type.getTypeSort()) {
        case Structure:
        case Sequence:
            return ti.getSize();
        case String:
        case URL:
            return Pointer.SIZE;
        case Enum:
            return getElementSize(TypeNotes.find(ti.enumbase));
        case Opaque:
            return ti.getSize();
        default:
            return type.getSize();
        }
    }

    protected Object
    getatomicdata(DapType basetype, long lcount, long elemsize, Nc4Pointer mem)
    {
        Object result = null;
        TypeSort sort = basetype.getTypeSort();
        int icount = (int) lcount;
        switch (sort) {
        case Char:
            // need to extract and convert utf8(really ascii) -> utf16
            byte[] bresult = mem.p.getByteArray(0, icount);
            char[] cresult = new char[bresult.length];
            for(int i = 0; i < icount; i++) {
                int ascii = bresult[i];
                ascii = ascii & 0x7F;
                cresult[i] = (char) ascii;
            }
            result = cresult;
            break;
        case UInt8:
        case Int8:
            result = mem.p.getByteArray(0, icount);
            break;
        case Int16:
        case UInt16:
            result = mem.p.getShortArray(0, icount);
            break;
        case Int32:
        case UInt32:
            result = mem.p.getIntArray(0, icount);
            break;
        case Int64:
        case UInt64:
            result = mem.p.getLongArray(0, icount);
            break;
        case Float32:
            result = mem.p.getFloatArray(0, icount);
            break;
        case Float64:
            result = mem.p.getDoubleArray(0, icount);
            break;
        case String:
        case URL:
            // TODO: properly free underlying strings
            result = mem.p.getStringArray(0, icount);
            break;
        case Opaque:
            ByteBuffer[] ops = new ByteBuffer[icount];
            result = ops;
            for(int i = 0; i < icount; i++) {
                ops[i] = mem.p.getByteBuffer(i * elemsize, elemsize);
            }
            break;
        case Enum:
            DapEnumeration de = (DapEnumeration) basetype;
            result = getatomicdata((DapType) de.getBaseType(), lcount, elemsize, mem);
            break;
        }
        return result;
    }

    static void
    slicesToVars(List<Slice> slices, SizeT[] startp, SizeT[] countp, SizeT[] stridep)
    {
        for(int i = 0; i < slices.size(); i++) {
            Slice slice = slices.get(i);
            startp[i] = new SizeT(slice.getFirst());
            countp[i] = new SizeT(slice.getCount());
            stridep[i] = new SizeT(slice.getStride());
        }
    }

    static public void
    errcheck(DapNetcdf nc4, int ret)
            throws DapException
    {
        if(ret != 0) {
            String msg = String.format("TestNetcdf: errno=%d; %s", ret, nc4.nc_strerror(ret));
            throw new DapException(msg);
        }
    }

    static public void
    readcheck(DapNetcdf nc4, int ret)
            throws DapException
    {
        try {
            errcheck(nc4, ret);
        } catch (DapException de) {
            throw new DapException(de);
        }
    }

    static SizeT[]
    indexToSizes(Index index)
    {
        SizeT[] sizes = new SizeT[index.getRank()];
        for(int i = 0; i < sizes.length; i++) {
            sizes[i] = new SizeT(index.get(i));
        }
        return sizes;
    }

    static long
    computeTrueOffset(Nc4Cursor c)
            throws DapException
    {
        long totaloffset = 0;
        List<Nc4Cursor> path = getCursorPath(c);
        Nc4Cursor current;

        // Walk all but the last element in path
        for(int i = 0; i < (path.size()-1); i++) {
            current = path.get(i);
            DapNode template = current.getTemplate();
            assert template.getSort().isVar();
            DapType dt = ((DapVariable)template).getBaseType();
            TypeNotes ti = (TypeNotes)dt.annotation();
            long size = ti.getSize();
            long offset = current.getOffset();
            long count = 0;
            switch (current.getScheme()) {
            case SEQUENCE:
            case STRUCTURE:
                Index index = current.getIndex();
                count = (index.getRank() == 0 ? 0 : index.index());
                break;
            case SEQARRAY:
            case STRUCTARRAY:
               assert false;
            }
            long delta = size * count + offset;
            totaloffset += delta;
        }
        current = path.get(path.size()-1);
        totaloffset += current.getOffset();
        return totaloffset;
    }

    /**
     * Given a cursor, get a list of "containing" cursors
     * with the following constraints.
     * 1. the first element in the path is a top-level variable.
     * 2. the remaining elements are the enclosing compound types
     * 3. the last element is the incoming cursor.
     * @param cursor
     * @return
     */
    static List<Nc4Cursor>
    getCursorPath(Nc4Cursor cursor)
    {
        List<Nc4Cursor> path = new ArrayList<>();
        for(;;) {
            Nc4Cursor next = (Nc4Cursor) cursor.getContainer();
            if(!cursor.getScheme().isArray())
                path.add(0,cursor);
            if(next == null) {
                assert cursor.getTemplate().isTopLevel();
                break;
            }
            cursor = next;
        }
        return path;
    }


    static Nc4Pointer
    getVarMemory(Nc4Cursor cursor)
    {
        while(cursor.getContainer() != null) {
            cursor = (Nc4Cursor) cursor.getContainer();
        }
        return cursor.getMemory();
    }

    protected void
    debug()
    {
        System.err.printf("CURSOR: %s%n",this.toString());
    }

}
