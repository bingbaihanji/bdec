package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

public final class Variable implements Value {

    private final int slot;

    private final int version;

    private final JavaType type;

    private final boolean isParameter;

    private final int originalIndex;

    private String name;

    public Variable(int slot, int version, JavaType type, boolean isParameter, int originalIndex) {
        this.slot = slot;
        this.version = version;
        this.type = type;
        this.isParameter = isParameter;
        this.originalIndex = originalIndex;
    }

    public int slot() {return slot;}

    public int version() {return version;}

    public String name() {return name != null ? name : "var" + originalIndex;}

    public void setName(String name) {this.name = name;}

    @Override
    public JavaType type() {return type;}

    public boolean isParameter() {return isParameter;}

    public int originalIndex() {return originalIndex;}

    @Override
    public String toString() {return name() + "(v" + version + ")";}

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Variable that)) {
            return false;
        }
        return slot == that.slot && version == that.version;
    }

    @Override
    public int hashCode() {return slot * 31 + version;}
}
