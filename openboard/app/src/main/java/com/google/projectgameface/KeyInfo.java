package com.google.projectgameface;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data class containing key information for AIDL communication
 */
public class KeyInfo implements Parcelable {
    public String label;
    public int keyCode;
    public float x;
    public float y;
    public int width;
    public int height;
    public boolean isVisible;
    
    public KeyInfo() {
        // Default constructor
    }
    
    public KeyInfo(String label, int keyCode, float x, float y, int width, int height, boolean isVisible) {
        this.label = label;
        this.keyCode = keyCode;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.isVisible = isVisible;
    }
    
    protected KeyInfo(Parcel in) {
        label = in.readString();
        keyCode = in.readInt();
        x = in.readFloat();
        y = in.readFloat();
        width = in.readInt();
        height = in.readInt();
        isVisible = in.readByte() != 0;
    }
    
    public static final Creator<KeyInfo> CREATOR = new Creator<KeyInfo>() {
        @Override
        public KeyInfo createFromParcel(Parcel in) {
            return new KeyInfo(in);
        }
        
        @Override
        public KeyInfo[] newArray(int size) {
            return new KeyInfo[size];
        }
    };
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(label);
        dest.writeInt(keyCode);
        dest.writeFloat(x);
        dest.writeFloat(y);
        dest.writeInt(width);
        dest.writeInt(height);
        dest.writeByte((byte) (isVisible ? 1 : 0));
    }
    
    @Override
    public String toString() {
        return "KeyInfo{" +
                "label='" + label + '\'' +
                ", keyCode=" + keyCode +
                ", x=" + x +
                ", y=" + y +
                ", width=" + width +
                ", height=" + height +
                ", isVisible=" + isVisible +
                '}';
    }
}
