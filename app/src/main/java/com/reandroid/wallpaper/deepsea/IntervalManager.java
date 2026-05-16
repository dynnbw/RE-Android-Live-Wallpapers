package com.reandroid.wallpaper.deepsea;

import java.util.ArrayList;
class IntervalManager {
    private static final int REF_WIDTH = 720;
    private static final int REF_HEIGHT = 1280;
    private final float MOVE_X = -4.5f;
    private final float MOVE_Y = -8.1f;
    private final float INTERVAL = 1.8f;
    private final float MARGIN = 0.1f;
    private float mIntervalX = 1.8f;
    private float mIntervalY = 1.8f;
    private float mMarginX = 0.1f;
    private float mMarginY = 0.1f;
    private float mMoveX = -4.5f;
    private float mMoveY = -8.1f;
    private final int mDevideX = 5;
    private final int mDevideY = 9;
    private ArrayList<IntervalVO> mList = null;
    private ArrayList<IntervalVO> mNotOccupiedList = null;

    public void initList() {
        this.mList = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            int i2 = i % 5;
            int i3 = i % 9;
            float f = this.mIntervalX;
            float f2 = this.mMoveX;
            float f3 = this.mIntervalX;
            float f4 = this.mMoveX;
            float f5 = this.mIntervalY;
            float f6 = this.mMoveY;
            float f7 = this.mIntervalY;
            float f8 = this.mMoveY;
            IntervalVO intervalVO = new IntervalVO();
            intervalVO.setID(i);
            intervalVO.setMinX((i2 * f) + f2 + this.mMarginX);
            intervalVO.setMaxX((((i2 + 1) * f3) + f4) - this.mMarginX);
            intervalVO.setMinY(this.mMarginY + (i3 * f5) + f6);
            intervalVO.setMaxY(this.mMarginY + ((i3 + 1) * f7) + f8);
            this.mList.add(intervalVO);
        }
    }

    public void setIsOccupiedToFalseByIndex(int i) {
        IntervalVO intervalVO = this.mList.get(i);
        intervalVO.setIsOccupied(false);
        if (this.mNotOccupiedList == null) {
            this.mNotOccupiedList = new ArrayList<>();
        }
        this.mNotOccupiedList.add(intervalVO);
    }

    public void setNotOccupiedList() {
        int size = this.mList.size();
        this.mNotOccupiedList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            IntervalVO intervalVO = this.mList.get(i);
            if (!intervalVO.isOccupied()) {
                this.mNotOccupiedList.add(intervalVO);
            }
        }
    }

    public IntervalVO getNotOccupiedVO() {
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int random = (int) (Math.random() * this.mNotOccupiedList.size());
        IntervalVO intervalVO = this.mNotOccupiedList.get(random);
        this.mNotOccupiedList.remove(random);
        return intervalVO;
    }

    public IntervalVO getNotOccupiedVOByZUp() {
        IntervalVO intervalVO;
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int size = this.mNotOccupiedList.size();
        int i = 0;
        while (true) {
            if (i >= size) {
                intervalVO = null;
                break;
            }
            intervalVO = this.mNotOccupiedList.get(i);
            int id = intervalVO.getID();
            int i2 = id % 9;
            if (id % 5 != 2 || i2 < 3 || i2 > 5) {
                i++;
            } else {
                this.mNotOccupiedList.remove(i);
                break;
            }
        }
        return intervalVO == null ? getNotOccupiedVO() : intervalVO;
    }

    public IntervalVO getNotOccupiedVOByZDown() {
        IntervalVO intervalVO;
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int size = this.mNotOccupiedList.size();
        for (int i = 0; i < size; i++) {
            intervalVO = this.mNotOccupiedList.get(i);
            int id = intervalVO.getID();
            int i2 = id % 9;
            if (id % 5 != 2 || i2 < 3 || i2 > 5) {
                this.mNotOccupiedList.remove(i);
                break;
            }
        }
        intervalVO = null;
        return intervalVO == null ? getNotOccupiedVO() : intervalVO;
    }

    public void changeMove(int i, int i2) {
        if (i > REF_WIDTH || i2 > REF_HEIGHT) {
            float f = (i / (float) REF_WIDTH) * 2.0f;
            this.mMoveX = (-4.5f) * f;
            this.mIntervalX = 1.8f * f;
            this.mMarginX = f * 0.1f;
            float f2 = (i2 / (float) REF_HEIGHT) * 2.0f;
            this.mMoveY = (-8.1f) * f2;
            this.mIntervalY = 1.8f * f2;
            this.mMarginY = f2 * 0.1f;
            return;
        }
        this.mMoveX = -4.5f;
        this.mIntervalX = 1.8f;
        this.mMarginX = 0.1f;
        this.mMoveY = -8.1f;
        this.mIntervalY = 1.8f;
        this.mMarginY = 0.1f;
    }
}
