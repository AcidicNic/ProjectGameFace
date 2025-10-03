package com.google.projectgameface;

import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data class containing key bounds information for AIDL communication
 */
public class KeyBounds implements Parcelable {
    public int left;
    public int top;
    public int right;
    public int bottom;
    public int keyCode;
    
    public KeyBounds() {
        // Default constructor
    }
    
    public KeyBounds(int left, int top, int right, int bottom, int keyCode) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.keyCode = keyCode;
    }
    
    public KeyBounds(Rect rect, int keyCode) {
        this.left = rect.left;
        this.top = rect.top;
        this.right = rect.right;
        this.bottom = rect.bottom;
        this.keyCode = keyCode;
    }
    
    protected KeyBounds(Parcel in) {
        left = in.readInt();
        top = in.readInt();
        right = in.readInt();
        bottom = in.readInt();
        keyCode = in.readInt();
    }
    
    public static final Creator<KeyBounds> CREATOR = new Creator<KeyBounds>() {
        @Override
        public KeyBounds createFromParcel(Parcel in) {
            return new KeyBounds(in);
        }
        
        @Override
        public KeyBounds[] newArray(int size) {
            return new KeyBounds[size];
        }
    };
    
    public Rect toRect() {
        return new Rect(left, top, right, bottom);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(left);
        dest.writeInt(top);
        dest.writeInt(right);
        dest.writeInt(bottom);
        dest.writeInt(keyCode);
    }
    
    @Override
    public String toString() {
        return "KeyBounds{" +
                "left=" + left +
                ", top=" + top +
                ", right=" + right +
                ", bottom=" + bottom +
                ", keyCode=" + keyCode +
                '}';
    }
}
