/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package dap4.cdm.dsp;

import dap4.cdm.CDMTypeFcns;
import dap4.core.data.DataCursor;
import dap4.core.dmr.*;
import dap4.core.util.*;
import dap4.dap4lib.AbstractCursor;
import ucar.ma2.Array;
import ucar.ma2.ArrayStructure;
import ucar.ma2.DataType;
import ucar.ma2.StructureMembers;

import java.util.List;

public class CDMCursor extends AbstractCursor
{

    //////////////////////////////////////////////////
    // Instance variables

    protected ucar.ma2.Array array = null;
    protected ucar.ma2.StructureData structdata = null; // scheme == STRUCTURE
    ucar.ma2.StructureMembers.Member member = null; // for field cursors

    //////////////////////////////////////////////////
    // Constructor(s)

    public CDMCursor(Scheme scheme, CDMDSP dsp, DapNode template, CDMCursor container)
            throws DapException
    {
        super(scheme, dsp, template, container);
    }

    public CDMCursor(CDMCursor c)
    {
        super(c);
        assert false;
        this.array = c.array;
        this.structdata = c.structdata;
        this.member = c.member;

    }

    //////////////////////////////////////////////////
    // AbstractCursor Abstract Methods

    @Override
    public Object
    read(List<Slice> slices)
            throws DapException
    {
        switch (this.scheme) {
        case ATOMIC:
            return readAtomic(slices);
        case STRUCTURE:
            if(((DapVariable) this.getTemplate()).getRank() > 0
                    || DapUtil.isScalarSlices(slices))
                throw new DapException("Cannot slice a scalar variable");
            DataCursor[] instances = new DataCursor[1];
            instances[0] = this;
            return instances;
        case SEQUENCE:
            if(((DapVariable) this.getTemplate()).getRank() > 0
                    || DapUtil.isScalarSlices(slices))
                throw new DapException("Cannot slice a scalar variable");
            instances = new DataCursor[1];
            instances[0] = this;
            return instances;
        case STRUCTARRAY:
            Odometer odom = Odometer.factory(slices);
            instances = new DataCursor[(int) odom.totalSize()];
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
    read(Index index)
            throws DapException
    {
        return read(DapUtil.indexToSlices(index));
    }

    @Override
    public CDMCursor
    readField(int findex)
            throws DapException
    {
        assert (this.scheme == scheme.RECORD || this.scheme == scheme.STRUCTURE);
        DapStructure basetype = (DapStructure)((DapVariable)getTemplate()).getBaseType();
        if(findex < 0 || findex >= basetype.getFields().size())
            throw new DapException("Field index out of range: " + findex);
        assert this.structdata != null;
        CDMCursor field = getFieldCursor(this,findex);
        return field;
    }

    protected CDMCursor
        getFieldCursor(CDMCursor container, int findex)
                throws DapException
        {
            // Now, create a cursors for a field f this instance
            DapVariable var = (DapVariable) getTemplate();
            DapStructure type = (DapStructure) var.getBaseType();
            DapVariable field = (DapVariable) type.getFields().get(findex);
            DapType ftype = field.getBaseType();
            Scheme scheme = schemeFor(field);
            CDMCursor fc = new CDMCursor(scheme, (CDMDSP) this.dsp, field, this);
            StructureMembers.Member member = this.structdata.getStructureMembers().getMember(findex);
            fc.setMember(member);
            fc.setArray(this.structdata.getArray(fc.member));
            return fc;
        }

    @Override
    public long
    getRecordCount()
    {
        assert (this.scheme == scheme.SEQUENCE);
        throw new UnsupportedOperationException("Not a Sequence");
    }

    @Override
    public CDMCursor
    readRecord(long i)
    {
        assert (this.scheme == scheme.SEQUENCE);
        throw new UnsupportedOperationException("Not a Sequence");
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
        assert slices != null && ((atomvar.getRank() == 0 && slices.size() == 1) || (slices.size() == atomvar.getRank()));
        return sliceAtomic(slices, this.array, atomvar);
    }

    protected Object
    sliceAtomic(List<Slice> slices, Array array, DapVariable var)
            throws DapException
    {
        List<DapDimension> dimset = var.getDimensions();
        DapType basetype = var.getBaseType();
        // If content.getDataType returns object, then we
        // really do not know its true datatype. So, as a rule,
        // we will rely on this.basetype.
        DataType datatype = CDMTypeFcns.daptype2cdmtype(basetype);
        if(datatype == null)
            throw new dap4.core.util.DapException("Unknown basetype: " + basetype);
        Object content = array.get1DJavaArray(datatype); // not very efficient; should do conversion
        Odometer odom = Odometer.factory(slices, dimset);
        Object data = CDMTypeFcns.createVector(basetype, odom.totalSize());
        for(int dstoffset = 0; odom.hasNext(); dstoffset++) {
            Index index = odom.next();
            long srcoffset = index.index();
            CDMTypeFcns.vectorcopy(basetype, content, data, srcoffset, dstoffset);
        }
        return data;
    }


    protected CDMCursor
    readStructure(Index index)
            throws DapException
    {
        assert (index != null);
        DapVariable var = (DapVariable) getTemplate();
        DapStructure type = (DapStructure) var.getBaseType();
        long pos = index.index();
        if(pos < 0 || pos > var.getCount())
            throw new IndexOutOfBoundsException("read: " + index);
        ArrayStructure sarray = (ArrayStructure) this.array;
        CDMCursor instance;
        assert (this.scheme == scheme.STRUCTARRAY);
        ucar.ma2.StructureData sd = sarray.getStructureData((int) pos);
        assert sd != null;
        instance = new CDMCursor(Scheme.STRUCTURE, (CDMDSP) this.dsp, var, null)
                .setStructureData(sd);
        instance.setIndex(index);
        return instance;
    }

    protected CDMCursor
    readSequence(Index index)
            throws DapException
    {
        assert (this.scheme == scheme.SEQARRAY);
        throw new UnsupportedOperationException();
    }

    //////////////////////////////////////////////////
    // CDMCursor Extensions

    public CDMCursor setArray(ucar.ma2.Array a)
    {
        this.array = a;
        return this;
    }

    public CDMCursor setStructureData(ucar.ma2.StructureData sd)
    {
        this.structdata = sd;
        return this;
    }

    public CDMCursor setMember(ucar.ma2.StructureMembers.Member m)
    {
        this.member = m;
        return this;
    }

    //////////////////////////////////////////////////
    // Utilities

    static Scheme
    schemeFor(DapVariable field)
    {
        DapType ftype = field.getBaseType();
        Scheme scheme = null;
        boolean isscalar = field.getRank() == 0;
        if(ftype.getTypeSort().isAtomic())
            scheme = Scheme.ATOMIC;
        else {
            if(ftype.getTypeSort().isStructType()) scheme = Scheme.STRUCTARRAY;
            else if(ftype.getTypeSort().isSeqType()) scheme = Scheme.SEQARRAY;
        }
        return scheme;
    }

}
