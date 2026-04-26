package com.reandroid.wallpaper.deepsea;

import java.util.ArrayList;
class JellyfishVO {
    private ArrayList<JellyfishVO> mAList;
    private ArrayList<JellyfishVO> mBList;
    private int mNumberOfJellyfish;
    private int mNumberOfParticle;
    private float mScaleOfJellyfish;
    private float mValueOfLight;
    private ArrayList<JellyfishVO> mList = null;
    private int mMaxNumberOfJellyfish = 10;
    private int mMaxNumberOfParticle = 200;
    private float[] mBlurColor = {0.0f, 0.0f, 1.0f, 1.0f};
    private float mRotate = 0.0f;
    private float mDestinationRotate = 0.0f;
    private float mPositionX = 0.0f;
    private float mPositionY = 0.0f;
    private float mPositionZ = 0.0f;
    private float mDestinationX = 0.0f;
    private float mDestinationY = 0.0f;
    private float mDestinationZ = 0.0f;
    private float mStartPositionX = 0.0f;
    private float mStartPositionY = 0.0f;
    private float mStartPositionZ = 0.0f;
    private float mMovingZDestinationX = 0.0f;
    private float mMovingZDestinationY = 0.0f;
    private float mMovingZDestinationZ = 0.0f;
    private float mMovingZStartX = 0.0f;
    private float mMovingZStartY = 0.0f;
    private float mMovingZStartZ = 0.0f;
    private float mDisport = 5.0f;
    private float mResistance = 120.0f;
    private float mShakingX = 0.0f;
    private float mShakingY = 0.0f;
    private float mShakingZ = 0.0f;
    private float mMaxDestinationX = 0.0f;
    private float mMinDestinationX = 0.0f;
    private float mMaxDestinationY = 0.0f;
    private float mMinDestinationY = 0.0f;
    private float mMaxDestinationZ = 0.0f;
    private float mMinDestinationZ = 0.0f;
    private float mAlpha = 1.0f;
    private boolean mIsRemoving = false;
    private boolean mIsAppearing = false;
    private boolean mIsRotating = false;
    private boolean mIsMovingZ = false;
    private boolean mHasBlur = false;
    private float mStartTime = 0.0f;
    private float mBlurTime = 0.0f;
    private int mIndexOfInterval = -1;
    private boolean mIsShakeUp = false;
    private boolean mIsShakeLeft = false;
    private boolean mIsShaking = false;
    private float mForceX = 0.0f;
    private float mForceY = 0.0f;
    private float mShakingR = 0.0f;
    private float mShakingO = 0.0f;
    private float mShakingAngle = 0.0f;
    private float mSpeed = 0.003f;
    private float mReverseAlpha = 1.0f;
    private boolean mIsFinishedAppearing = false;
    private boolean mIsFinishedRemoving = false;
    private final float mLowBatteryDuration = 50000.0f;
    private final float mLowBatteryDurationZ = 2000.0f;
    private final float mLowBatterySpeed = 0.002f;
    private int mABDivide = 0;

    public JellyfishVO() {
        this.mNumberOfJellyfish = 0;
        this.mNumberOfParticle = 0;
        this.mScaleOfJellyfish = 1.0f;
        this.mValueOfLight = 1.0f;
        this.mNumberOfJellyfish = 11;
        this.mNumberOfParticle = 200;
        this.mScaleOfJellyfish = 1.0f;
        this.mValueOfLight = 1.0f;
    }

    public void initList() {
        if (this.mList != null) {
            this.mList = null;
        }
        this.mList = new ArrayList<>();
        int i = this.mNumberOfJellyfish;
        for (int i2 = 0; i2 < i; i2++) {
            JellyfishVO jellyfishVO = new JellyfishVO();
            jellyfishVO.setPosition(0.0f, 0.9f - (i2 * 0.21f), -0.5f);
            if (i2 < i * 0.5d) {
                jellyfishVO.setIsShakeUp(true);
            }
            this.mList.add(jellyfishVO);
        }
    }

    public void initMovingZ() {
        int size = this.mList.size();
        for (int i = 0; i < size; i++) {
            this.mList.get(i).setIsMovingZ(false);
        }
    }

    public void setNumberOfJellyfish(int i) {
        this.mNumberOfJellyfish = i;
    }

    public int getNumberOfJellyfish() {
        return this.mNumberOfJellyfish;
    }

    public JellyfishVO getVOByIndex(int i) {
        return this.mList.get(i);
    }

    public JellyfishVO getRandomVOByZDown() {
        ArrayList<JellyfishVO> listByZDown = getListByZDown();
        int size = listByZDown.size();
        if (size % 2 != 0) {
            size--;
        }
        int random = (int) (Math.random() * size);
        if (size <= 0) {
            return null;
        }
        return listByZDown.get(random);
    }

    public JellyfishVO getRandomVOByZUp() {
        ArrayList<JellyfishVO> listByZUp = getListByZUp();
        int size = listByZUp.size();
        if (size % 2 != 0) {
            size--;
        }
        int random = (int) (Math.random() * size);
        if (size <= 0) {
            return null;
        }
        return listByZUp.get(random);
    }

    private ArrayList<JellyfishVO> getListByZDown() {
        int size = this.mList.size();
        ArrayList<JellyfishVO> arrayList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            JellyfishVO jellyfishVO = this.mList.get(i);
            if (jellyfishVO.getPositionZ() < -6.0f) {
                arrayList.add(jellyfishVO);
            }
        }
        return arrayList;
    }

    private ArrayList<JellyfishVO> getListByZUp() {
        int size = this.mList.size();
        ArrayList<JellyfishVO> arrayList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            JellyfishVO jellyfishVO = this.mList.get(i);
            if (jellyfishVO.getPositionZ() >= -6.0f) {
                arrayList.add(jellyfishVO);
            }
        }
        return arrayList;
    }

    public int getMaxNumberOfJellyfish() {
        return this.mMaxNumberOfJellyfish;
    }

    public boolean isAllFinishedAppearing() {
        boolean z = true;
        int i = this.mNumberOfJellyfish;
        int i2 = 0;
        while (i2 < i) {
            boolean z2 = !this.mList.get(i2).isFinishedAppearing() ? false : z;
            i2++;
            z = z2;
        }
        return z;
    }

    public boolean isAllFinishedRemoving(int i) {
        int i2 = this.mNumberOfJellyfish;
        int i3 = 0;
        int i4 = 0;
        while (i4 < i2) {
            int i5 = this.mList.get(i4).isFinishedRemoving() ? i3 + 1 : i3;
            i4++;
            i3 = i5;
        }
        return i3 >= i;
    }

    public void setABList() {
        int size = this.mList.size();
        this.mABDivide = (size * 8) / 10;
        this.mAList = new ArrayList<>();
        this.mBList = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (i < this.mABDivide) {
                this.mAList.add(this.mList.get(i));
            } else {
                this.mBList.add(this.mList.get(i));
            }
        }
    }

    public ArrayList<JellyfishVO> getAList() {
        return this.mAList;
    }

    public ArrayList<JellyfishVO> getBList() {
        return this.mBList;
    }

    public int getABDivide() {
        return this.mABDivide;
    }

    public void setPosition(float f, float f2, float f3) {
        this.mPositionX = f;
        this.mPositionY = f2;
        this.mPositionZ = f3;
    }

    public float getPositionX() {
        return this.mPositionX;
    }

    public float getPositionY() {
        return this.mPositionY;
    }

    public float getPositionZ() {
        return this.mPositionZ;
    }

    public void setDestination(float f, float f2, float f3) {
        this.mDestinationX = f;
        this.mDestinationY = f2;
        this.mDestinationZ = f3;
    }

    public float getDestinationX() {
        return this.mDestinationX;
    }

    public float getDestinationY() {
        return this.mDestinationY;
    }

    public float getDestinationZ() {
        return this.mDestinationZ;
    }

    public void setStartPosition(float f, float f2, float f3) {
        this.mStartPositionX = f;
        this.mStartPositionY = f2;
        this.mStartPositionZ = f3;
    }

    public float getStartPositionX() {
        return this.mStartPositionX;
    }

    public float getStartPositionY() {
        return this.mStartPositionY;
    }

    public void setMovingZDestination(float f, float f2, float f3) {
        this.mMovingZDestinationX = f;
        this.mMovingZDestinationY = f2;
        this.mMovingZDestinationZ = f3;
    }

    public float getMovingZDestinationX() {
        return this.mMovingZDestinationX;
    }

    public float getMovingZDestinationY() {
        return this.mMovingZDestinationY;
    }

    public float getMovingZDestinationZ() {
        return this.mMovingZDestinationZ;
    }

    public void setMovingZStartPosition(float f, float f2, float f3) {
        this.mMovingZStartX = f;
        this.mMovingZStartY = f2;
        this.mMovingZStartZ = f3;
    }

    public float getMovingZStartX() {
        return this.mMovingZStartX;
    }

    public float getMovingZStartY() {
        return this.mMovingZStartY;
    }

    public void setShakingX(float f) {
        this.mShakingX = f;
    }

    public void setRotate(float f) {
        this.mRotate = f;
    }

    public float getRotate() {
        return this.mRotate;
    }

    public void setDestinationRotate(float f) {
        this.mDestinationRotate = f;
    }

    public float getDestinationRotate() {
        return this.mDestinationRotate;
    }

    public void setIsRemoving(boolean z) {
        this.mIsRemoving = z;
    }

    public boolean isRemoving() {
        return this.mIsRemoving;
    }

    public void setIsAppearing(boolean z) {
        this.mIsAppearing = z;
    }

    public boolean isAppearing() {
        return this.mIsAppearing;
    }

    public void setIsMovingZ(boolean z) {
        this.mIsMovingZ = z;
    }

    public boolean isMovingZ() {
        if (this.mIsMovingZ && this.mPositionZ <= this.mMovingZDestinationZ + 0.01f && this.mPositionZ >= this.mMovingZDestinationZ - 0.01f) {
            this.mIsMovingZ = false;
            this.mDestinationZ = this.mMovingZDestinationZ;
            if (this.mMovingZDestinationX < 0.0f) {
                this.mDestinationX = this.mMovingZDestinationX;
            } else {
                this.mDestinationX = this.mMovingZDestinationX;
            }
            if (this.mMovingZDestinationY < 0.0f) {
                this.mDestinationY = this.mMovingZDestinationY;
            } else {
                this.mDestinationY = this.mMovingZDestinationY;
            }
        }
        return this.mIsMovingZ;
    }

    public void setHasBlur(boolean z) {
        this.mHasBlur = z;
    }

    public boolean hasBlur() {
        return this.mHasBlur;
    }

    public void setStartTime(float f) {
        this.mStartTime = f;
    }

    public void setIndexOfInterval(int i) {
        this.mIndexOfInterval = i;
    }

    public int getIndexOfInterval() {
        return this.mIndexOfInterval;
    }

    public boolean isShakeUp() {
        return this.mIsShakeUp;
    }

    public void setIsShakeUp(boolean z) {
        this.mIsShakeUp = z;
    }

    public void setForceX(float f) {
        this.mForceX = f;
    }

    public float getForceX() {
        return this.mForceX;
    }

    public void setForceY(float f) {
        this.mForceY = f;
    }

    public float getForceY() {
        return this.mForceY;
    }

    public void setIsShaking(boolean z) {
        this.mIsShaking = z;
    }

    public boolean isShaking() {
        return this.mIsShaking;
    }

    public void setShakingR(float f) {
        this.mShakingR = f;
    }

    public void setShakingO(float f) {
        this.mShakingO = f;
    }

    public void setShakingAngle(float f) {
        this.mShakingAngle = f;
    }

    public void setSpeed(float f) {
        this.mSpeed = f;
    }

    public float getSpeed() {
        return this.mSpeed;
    }

    public void setReverseAlpha(float f) {
        this.mReverseAlpha = f;
    }

    public float getReverseAlpha() {
        return this.mReverseAlpha;
    }

    public void setIsFinishedAppearing(boolean z) {
        this.mIsFinishedAppearing = z;
    }

    public boolean isFinishedAppearing() {
        return this.mIsFinishedAppearing;
    }

    public void setIsFinishedRemoving(boolean z) {
        this.mIsFinishedRemoving = z;
    }

    public boolean isFinishedRemoving() {
        return this.mIsFinishedRemoving;
    }

    public float getLowBatterySpeed() {
        return 0.002f;
    }

    public float getDisport() {
        return this.mDisport;
    }

    public float getResistance() {
        return this.mResistance;
    }
}
