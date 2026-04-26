package com.reandroid.wallpaper.deepsea;

class IntervalVO {
    private int mID = -1;
    private float mMinX = 0.0f;
    private float mMaxX = 0.0f;
    private float mMinY = 0.0f;
    private float mMaxY = 0.0f;
    private boolean mIsOccupied = false;

    public void setID(int i) {
        this.mID = i;
    }

    public int getID() {
        return this.mID;
    }

    public void setMinX(float f) {
        this.mMinX = f;
    }

    public float getMinX() {
        return this.mMinX;
    }

    public void setMaxX(float f) {
        this.mMaxX = f;
    }

    public float getMaxX() {
        return this.mMaxX;
    }

    public void setMinY(float f) {
        this.mMinY = f;
    }

    public float getMinY() {
        return this.mMinY;
    }

    public void setMaxY(float f) {
        this.mMaxY = f;
    }

    public float getMaxY() {
        return this.mMaxY;
    }

    public void setIsOccupied(boolean z) {
        this.mIsOccupied = z;
    }

    public boolean isOccupied() {
        return this.mIsOccupied;
    }
}
