/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information. */

package dap4.dap4lib.netcdf;

import dap4.core.dmr.*;
import dap4.core.util.DapSort;

import java.util.HashMap;
import java.util.Map;

import static dap4.dap4lib.netcdf.DapNetcdf.*;

/**
 * Note that ideally, this info should be part of the
 * Nc4DMR classes, but that would require multiple inheritance.
 * Hence, we isolate that info here and add it to the instances
 * via annotation
 */

abstract public class Nc4Notes
{
    //////////////////////////////////////////////////
    // Constants

    // Mnemonics
    static public final int NOGROUP = -1;
    static public final int NOID = -1;
    static public final int NOFIELDID = -1;

    //////////////////////////////////////////////////
    // Type Decls

    static public enum NoteSort
    {
        TYPE, VAR, GROUP, DIM;
    }

    static public class Notes implements Cloneable
    {
        NoteSort sort;
        int gid;
        int id;
        protected String name = null;
        DapNode node = null;
        protected Notes parent = null;
        protected TypeNotes basetype = null;
        protected long size = 0;
        protected long offset = 0;

        public Notes(int gid, int id)
        {
            this.gid = gid;
            this.id = id;
            if(this instanceof TypeNotes) this.sort = NoteSort.TYPE;
            else if(this instanceof VarNotes) this.sort = NoteSort.VAR;
            else if(this instanceof GroupNotes) this.sort = NoteSort.GROUP;
            else if(this instanceof DimNotes) this.sort = NoteSort.DIM;
        }

        public NoteSort getSort()
        {
            return this.sort;
        }

        public Notes setName(String name)
        {
            this.name = name;
            return this;
        }

        public Notes set(DapNode node)
        {
            this.node = node;
            node.annotate(this);
            if(this.name != null) setName(node.getShortName());
            return this;
        }

        public DapNode get()
        {
            return this.node;
        }

        public Notes setContainer(Notes parent)
        {
            this.parent = parent;
            return this;
        }

        public Notes getContainer()
        {
            return this.parent;
        }

        public Notes setSize(long size)
        {
            this.size = size;
            return this;
        }

        public long getOffset()
        {
            return this.offset;
        }

        public Notes setOffset(long offset)
        {
            this.offset = offset;
            return this;
        }

        public long getSize()
        {
            return this.size;
        }

        public Notes setBaseType(TypeNotes t)
        {
            this.basetype = t;
            return this;
        }

        public TypeNotes getBaseType()
        {
            return this.basetype;
        }

        DapGroup group()
        {
            GroupNotes g = GroupNotes.find(gid);
            return (g == null ? null : g.get());
        }

        public String toString()
        {
            StringBuilder buf = new StringBuilder();
            buf.append(this.getClass().getName());
            buf.append("{");
            if(name != null) {
                buf.append("name=");
                buf.append(name);
            }
            buf.append("node=");
            buf.append(this.node != null ? this.node.getShortName() : "null");
            buf.append("}");
            return buf.toString();
        }
    }

    static public class GroupNotes extends Notes
    {
        static Map<Integer, GroupNotes> allgroups = new HashMap<>();

        static public GroupNotes find(int gid)
        {
            return allgroups.get(gid);
        }

        public GroupNotes(int p, int g)
        {
            super(p, g);
            allgroups.put(g, this);
        }

        public DapGroup get()
        {
            return (DapGroup) super.get();
        }

        public GroupNotes set(DapNode node)
        {
            return (GroupNotes) super.set(node);
        }

    }

    static public class DimNotes extends Notes
    {
        static Map<Integer, DimNotes> alldims = new HashMap<>();

        static public DimNotes find(int id)
        {
            return alldims.get(id);
        }

        public DimNotes(int g, int id)
        {
            super(g, id);
            alldims.put(id, this);
        }

        public DapDimension get()
        {
            return (DapDimension) super.get();
        }

        public DimNotes set(DapNode node)
        {
            return (DimNotes) super.set(node);
        }

    }

    static public class TypeNotes extends Notes
    {
        static Map<Integer, TypeNotes> alltypes = new HashMap<>();

        static public TypeNotes find(int id)
        {
            return alltypes.get(id);
        }

        static public TypeNotes find(DapType dt)
        {
            for(Map.Entry<Integer, TypeNotes> entry : alltypes.entrySet()) {
                if(entry.getValue().getType() == dt) {
                    return entry.getValue();
                }
            }
            return null;
        }

        public int enumbase = -1;
        public boolean isvlen = false;

        public TypeNotes(int g, int id)
        {
            super(g, id);
            alltypes.put(id, this);
        }

        public DapType getType()
        {
            DapSort sort = this.node.getSort();
            switch (sort) {
            case ATOMICTYPE:
            case STRUCTURE:
            case SEQUENCE:
                return (DapType) super.get();
            case ENUMERATION:
                return (DapEnumeration) super.get();
            case VARIABLE:
                return ((DapVariable) super.get()).getBaseType();
            default:
                break;
            }
            return null;
        }

        public TypeNotes setOpaque(int len)
        {
            super.setSize(len);
            return this;
        }

        public TypeNotes setEnumBaseType(int bt)
        {
            this.enumbase = bt;
            return this;
        }

        public boolean isOpaque()
        {
            return getType().getTypeSort().isOpaqueType();
        }

        public boolean isEnum()
        {
            return getType().getTypeSort().isEnumType();
        }

        public boolean isCompound()
        {
            return getType().getTypeSort().isCompoundType();
        }

        public boolean isVlen()
        {
            return this.isvlen;
        }

        public TypeNotes setCompoundSize(int size)
        {
            super.setSize(size);
            return this;
        }

        public TypeNotes markVlen()
        {
            this.isvlen = true;
            return this;
        }

        public DapType get()
        {
            return (DapType) super.get();
        }

        public TypeNotes set(DapNode node)
        {
            return (TypeNotes) super.set(node);
        }

        static {
            new TypeNotes(0, NC_BYTE).set(DapType.INT8);
            new TypeNotes(0, NC_CHAR).set(DapType.CHAR);
            new TypeNotes(0, NC_SHORT).set(DapType.INT16);
            new TypeNotes(0, NC_INT).set(DapType.INT32);
            new TypeNotes(0, NC_FLOAT).set(DapType.FLOAT32);
            new TypeNotes(0, NC_DOUBLE).set(DapType.FLOAT64);
            new TypeNotes(0, NC_UBYTE).set(DapType.UINT8);
            new TypeNotes(0, NC_USHORT).set(DapType.UINT16);
            new TypeNotes(0, NC_UINT).set(DapType.UINT32);
            new TypeNotes(0, NC_INT64).set(DapType.INT64);
            new TypeNotes(0, NC_UINT64).set(DapType.UINT64);
            new TypeNotes(0, NC_STRING).set(DapType.STRING);
        }

    }

    static public class VarNotes extends Notes
    {
        static Map<Long, VarNotes> allvars = new HashMap<>();

        static public VarNotes find(int gid, int vid)
        {
            long gv = (((long) gid) << 32) | vid;
            return allvars.get(gv);
        }

        protected int fieldid = -1;

        public VarNotes(int g, int v)
        {
            super(g, v);
            long gv = (((long) g) << 32) | v;
            allvars.put(gv, this);

        }

        public VarNotes setBaseType(TypeNotes ti)
        {
            return (VarNotes) super.setBaseType(ti);
        }

        public DapVariable get()
        {
            return (DapVariable) super.get();
        }

        public VarNotes set(DapNode node)
        {
            return (VarNotes) super.set(node);
        }

        public int getFieldID()
        {
            return this.fieldid;
        }

        public VarNotes setFieldID(int id)
        {
            this.fieldid = id;
            return this;
        }

    }


}
