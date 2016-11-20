/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information. */

package dap4.dap4lib.netcdf;

import dap4.core.dmr.*;
import dap4.core.util.DapSort;
import dap4.core.util.DapUtil;

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
        Nc4DSP dsp; // Need a place to store global state
        NoteSort sort;
        int gid;
        int id;
        protected String name = null;
        DapNode node = null;
        protected Notes parent = null;
        protected TypeNotes basetype = null;
        protected long size = 0;
        protected long offset = 0;

        public Notes(int gid, int id, Nc4DSP dsp)
        {
            this.dsp = dsp;
            this.gid = gid;
            this.id = id;
            if(this instanceof TypeNotes) this.sort = NoteSort.TYPE;
            else if(this instanceof VarNotes) this.sort = NoteSort.VAR;
            else if(this instanceof GroupNotes) this.sort = NoteSort.GROUP;
            else if(this instanceof DimNotes) this.sort = NoteSort.DIM;
            dsp.note(this);
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
            GroupNotes g = (GroupNotes)dsp.find(gid,NoteSort.GROUP);
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
        public GroupNotes(int p, int g, Nc4DSP dsp)
        {
            super(p, g, dsp);
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
        public DimNotes(int g, int id, Nc4DSP dsp)
        {
            super(g, id, dsp);
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
        public int enumbase = -1;
        public boolean isvlen = false;

        public TypeNotes(int g, int id, Nc4DSP dsp)
        {
            super(g, id, dsp);
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

        public TypeNotes setOpaque(long len)
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

        public TypeNotes setCompoundSize(long size)
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

    }

    static public class VarNotes extends Notes
    {
//            long gv = (((long) gid) << 32) | vid;

        protected int fieldid = -1;

        public VarNotes(int g, int v, Nc4DSP dsp)
        {
            super(g, v, dsp);
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

        @Override
        public long getSize()
        {
            return this.getBaseType().getSize() * DapUtil.dimProduct(get().getDimensions());
        }

    }


}
