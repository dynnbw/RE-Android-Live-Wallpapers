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
        for (int gridIndex = 0; gridIndex < 45; gridIndex++) {
            int columnIndex = gridIndex % 5;
            int rowIndex = gridIndex % 9;
            float intervalX = this.mIntervalX;
            float moveX = this.mMoveX;
            float intervalX2 = this.mIntervalX;
            float moveX2 = this.mMoveX;
            float intervalY = this.mIntervalY;
            float moveY = this.mMoveY;
            float intervalY2 = this.mIntervalY;
            float moveY2 = this.mMoveY;
            IntervalVO intervalVO = new IntervalVO();
            intervalVO.setID(gridIndex);
            intervalVO.setMinX((columnIndex * intervalX) + moveX + this.mMarginX);
            intervalVO.setMaxX((((columnIndex + 1) * intervalX2) + moveX2) - this.mMarginX);
            intervalVO.setMinY(this.mMarginY + (rowIndex * intervalY) + moveY);
            intervalVO.setMaxY(this.mMarginY + ((rowIndex + 1) * intervalY2) + moveY2);
            this.mList.add(intervalVO);
        }
    }

    public void setIsOccupiedToFalseByIndex(int index) {
        IntervalVO intervalVO = this.mList.get(index);
        intervalVO.setIsOccupied(false);
        if (this.mNotOccupiedList == null) {
            this.mNotOccupiedList = new ArrayList<>();
        }
        this.mNotOccupiedList.add(intervalVO);
    }

    public void setNotOccupiedList() {
        int size = this.mList.size();
        this.mNotOccupiedList = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            IntervalVO intervalVO = this.mList.get(index);
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
        IntervalVO result;
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int size = this.mNotOccupiedList.size();
        int listIndex = 0;
        while (true) {
            if (listIndex >= size) {
                result = null;
                break;
            }
            result = this.mNotOccupiedList.get(listIndex);
            int id = result.getID();
            int rowIndex = id % 9;
            if (id % 5 != 2 || rowIndex < 3 || rowIndex > 5) {
                listIndex++;
            } else {
                this.mNotOccupiedList.remove(listIndex);
                break;
            }
        }
        return result == null ? getNotOccupiedVO() : result;
    }

    public IntervalVO getNotOccupiedVOByZDown() {
        IntervalVO result;
        if (this.mNotOccupiedList == null || this.mNotOccupiedList.isEmpty()) {
            return null;
        }
        int size = this.mNotOccupiedList.size();
        for (int listIndex = 0; listIndex < size; listIndex++) {
            result = this.mNotOccupiedList.get(listIndex);
            int id = result.getID();
            int rowIndex = id % 9;
            if (id % 5 != 2 || rowIndex < 3 || rowIndex > 5) {
                this.mNotOccupiedList.remove(listIndex);
                break;
            }
        }
        result = null;
        return result == null ? getNotOccupiedVO() : result;
    }

    public void changeMove(int width, int height) {
        if (width > REF_WIDTH || height > REF_HEIGHT) {
            float xScaleFactor = (width / (float) REF_WIDTH) * 2.0f;
            this.mMoveX = (-4.5f) * xScaleFactor;
            this.mIntervalX = 1.8f * xScaleFactor;
            this.mMarginX = xScaleFactor * 0.1f;
            float yScaleFactor = (height / (float) REF_HEIGHT) * 2.0f;
            this.mMoveY = (-8.1f) * yScaleFactor;
            this.mIntervalY = 1.8f * yScaleFactor;
            this.mMarginY = yScaleFactor * 0.1f;
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
