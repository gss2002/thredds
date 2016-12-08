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

    static public boolean DEBUG = false;

    //////////////////////////////////////////////////
    // Instance variables

    protected Nc4Pointer memory = null;

    //////////////////////////////////////////////////
    // Constructor(s)

    public Nc4Cursor(Scheme scheme, Nc4DSP dsp, DapVariable template, Nc4Cursor container)
            throws DapException
    {
        super(scheme, dsp, template, container);
        if(scheme == Scheme.SEQUENCE) {
            int x = 0;
        }
        if(DEBUG) debug();
    }

    public Nc4Cursor(Nc4Cursor c)
    {
        super(c);
        assert false;
        this.memory = c.getMemory();
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
        case STRUCTURE:
        case SEQUENCE:
            if(((DapVariable) this.getTemplate()).getRank() > 0
                    || DapUtil.isScalarSlices(slices))
                throw new DapException("Cannot slice a scalar variable");
            return this;
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
    public Nc4Cursor
    readField(int findex)
            throws DapException
    {
        assert (this.scheme == scheme.RECORD || this.scheme == scheme.STRUCTURE);
        DapVariable template = (DapVariable) getTemplate();
        DapStructure struct = (DapStructure) template.getBaseType();
        if(findex < 0 || findex >= struct.getFields().size())
            throw new DapException("Field index out of range: " + findex);
        DapVariable field = struct.getField(findex);
        // Get VarNotes and TypeNotes
        VarNotes fi = (VarNotes)((Nc4DSP)getDSP()).find(field);
        TypeNotes ti = fi.getBaseType();
        long elemsize = getElementSize(ti); // read only one instance
        long totalsize;
        if(this.getScheme() == Scheme.RECORD) {
            totalsize = elemsize; // Record instances act like scalars
        } else { // Structure
            long dimproduct = DapUtil.dimProduct(template.getDimensions());
            totalsize = elemsize * dimproduct;
        }
        Nc4Cursor cursor = null;
        TypeSort typesort = ti.getType().getTypeSort();
        if(typesort.isAtomic()) {
            cursor = new Nc4Cursor(Scheme.ATOMIC, (Nc4DSP) this.dsp, field, this);
        } else if(typesort == TypeSort.Structure) {
            cursor = new Nc4Cursor(Scheme.STRUCTARRAY, (Nc4DSP) this.dsp, field, this);
        } else if(typesort == TypeSort.Sequence) {
            cursor = new Nc4Cursor(Scheme.SEQARRAY, (Nc4DSP) this.dsp, field, this);
        }
        // as a rule, a field's memory is its parent container memory.
        cursor.setMemory(getMemory());
        return cursor;
    }

    @Override
    public long
    getRecordCount()
    {
        assert (this.scheme == scheme.SEQUENCE);
        if(this.recordcount < 0)
            throw new IllegalStateException("Sequence has no record count");
        return this.recordcount;
    }

    @Override
    public Nc4Cursor
    readRecord(long i)
            throws DapException
    {
        assert (this.scheme == scheme.SEQUENCE);
        if(i < 0 || i >= getRecordCount())
            throw new ArrayIndexOutOfBoundsException("Illegal record id: " + i);
        VarNotes vn = (VarNotes)((Nc4DSP)getDSP()).find(getTemplate());

        long size1 = vn.getBaseType().getSize();
        long offset = size1 * i;
        Nc4Pointer instance = getMemory();
        Nc4Pointer mem = instance.share(offset, size1);
        Nc4Cursor rec = new Nc4Cursor(Scheme.RECORD, (Nc4DSP) getDSP(), (DapVariable) getTemplate(), this);
        rec.setMemory(mem);
        rec.setRecordIndex(i);
        return rec;
    }


    @Override
    public Index
    getIndex()
            throws DapException
    {
        if(this.scheme != Scheme.STRUCTURE && this.scheme != Scheme.SEQUENCE)
            throw new DapException("Not a Sequence|Structure instance");
        return this.arrayindex;
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
        assert slices != null && ((rank == 0 && slices.size() == 1) || (slices.size() == rank));
        // Get VarNotes and TypeNotes
        Notes n = ((Nc4DSP)this.dsp).find(this.template);
        Object result = null;
        long count = DapUtil.sliceProduct(slices);
        VarNotes vn = (VarNotes) n;
        TypeNotes ti = vn.getBaseType();
        if(getContainer() == null) {
            if(rank == 0) { //scalar
                result = readAtomicScalar(vn, ti);
            } else {
                result = readAtomicVector(vn, ti, count, slices);
            }
        } else {// field of a structure instance or record
            long elemsize = ((DapType) ti.get()).getSize();
            assert (this.container != null);
            long trueoffset = computeTrueOffset(this);
            Nc4Pointer varmem = getMemory();
            Nc4Pointer mem = varmem.share(trueoffset, count * elemsize);
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
    readStructure(Index index)
            throws DapException
    {
        assert (index != null);
        assert this.scheme == Scheme.STRUCTARRAY;
        DapVariable template = (DapVariable) getTemplate();
        VarNotes vi = (VarNotes) ((Nc4DSP)this.dsp).find(template);
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
    readSequence(Index index)
            throws DapException
    {
        assert (index != null);
        assert this.scheme == Scheme.SEQARRAY;
        DapVariable template = (DapVariable) getTemplate();
        VarNotes vi = (VarNotes) ((Nc4DSP)this.dsp).find(template);
        TypeNotes ti = vi.basetype;
        Nc4Pointer mem;
        Nc4Cursor cursor = null;
        DapNetcdf.Vlen_t[] vlen = new DapNetcdf.Vlen_t[1];
        cursor = new Nc4Cursor(Scheme.SEQUENCE, (Nc4DSP) this.dsp, template, this);
        if(template.isTopLevel()) {
            int ret;
            DapNetcdf nc4 = ((Nc4DSP) this.dsp).getJNI();
            SizeT[] sizes = indexToSizes(index);
            readcheck(nc4, ret = nc4.nc_get_var1(vi.gid, vi.id, sizes, vlen));
        } else {// field of a structure instance or record
            long pos = index.index();
            if(pos < 0 || pos >= template.getCount())
                throw new IndexOutOfBoundsException("read: " + index);
            // We need to extract the vlen for this field
            Nc4Pointer pp = ((Nc4Cursor) getContainer()).getMemory();
            int vlensize = DapNetcdf.Vlen_t.VLENSIZE;
            pp = pp.share(pos * vlensize, vlensize);
            vlen[0] = new DapNetcdf.Vlen_t(pp.p);
            vlen[0].read();
        }
        cursor.setRecordCount(vlen[0].len);
        long memsize = ti.getSize() * cursor.getRecordCount();
        mem = new Nc4Pointer(vlen[0].p, memsize);
        cursor.setMemory(mem);
        cursor.setIndex(index);
        return cursor;
    }

    //////////////////////////////////////////////////
    // Nc4Cursor Extensions

    public long
    getOffset()
    {
        DapVariable dv = (DapVariable) getTemplate();
        Notes n = ((Nc4DSP)this.dsp).find(dv);
        return n.getOffset();
    }

    public long
    getElementSize()
    {
        DapVariable dv = (DapVariable) getTemplate();
        Notes n = ((Nc4DSP)this.dsp).find(dv);
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
    // Utilities

    protected long
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
            return getElementSize((TypeNotes) ((Nc4DSP) getDSP()).find(ti.enumbase, NoteSort.TYPE));
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

    /**
     * Given a field ref, compute the true offset with respect to
     * it top-level containing structure/record
     *
     * @param f field cursor
     * @return
     * @throws DapException
     */
    long
    computeTrueOffset(Nc4Cursor f)
            throws DapException
    {
        List<Nc4Cursor> path = getCursorPath(f);
        long totaloffset = 0;
        Nc4Cursor current;

        // First element is presumed to be a structure ore record variable,
        // and that its memory covers only it's instance.
        // Walk intermediate nodes
        for(int i = 1; i < (path.size() - 1); i++) {
            current = path.get(i);
            DapVariable template = (DapVariable) current.getTemplate();
            TypeNotes ti = (TypeNotes)((Nc4DSP)getDSP()).find(template);

            long size = ti.getSize();
            long offset = current.getOffset();
            long pos = 0;
            switch (current.getScheme()) {
            case SEQUENCE:
            case STRUCTURE:
                pos = current.getIndex().index();
                break;
            case RECORD:
                // readrecord will have set our memory to the start of the record
                pos = 0;
                break;
            default:
                throw new DapException("Illegal cursor type: " + current.getScheme());
            }
            long delta = size * pos + offset;
            totaloffset += delta;
        }
        assert path.get(path.size() - 1) == f;
        totaloffset += f.getOffset();
        return totaloffset;
    }

    /**
     * Given a cursor, get a list of "containing" cursors
     * with the following constraints.
     * 1. the first element in the path is a top-level variable.
     * 2. the remaining elements are the enclosing compound types
     * 3. the last element is the incoming cursor.
     *
     * @param cursor
     * @return
     */
    static List<Nc4Cursor>
    getCursorPath(Nc4Cursor cursor)
    {
        List<Nc4Cursor> path = new ArrayList<>();
        for(; ; ) {
            if(!cursor.getScheme().isCompoundArray()) // suppress
                path.add(0, cursor);
            if(cursor.getScheme() == Scheme.SEQUENCE) {
                // Stop here because the sequence has the vlen mem as its mem
                break;
            }
            Nc4Cursor next = (Nc4Cursor) cursor.getContainer();
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
        System.err.printf("CURSOR: %s%n", this.toString());
    }

}
