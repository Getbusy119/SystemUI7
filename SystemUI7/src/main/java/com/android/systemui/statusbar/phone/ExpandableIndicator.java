/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.systemui.R;

public class ExpandableIndicator extends ImageView {

    private boolean mExpanded;
    private boolean mIsDefaultDirection = true;

    public ExpandableIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        final int res = getDrawableResourceId(mExpanded);
        setImageResource(res);
        setContentDescription(getContentDescription(mExpanded));
    }

    public void setExpanded(boolean expanded) {
        if (expanded == mExpanded) return;
        mExpanded = expanded;
        final int res = getDrawableResourceId(!mExpanded);

        // workaround to reset drawable
        //added by chenzhengjun start
        setImageResource(res);
//        final AnimatedVectorDrawable avd = (AnimatedVectorDrawable) getContext()
//                .getDrawable(res).getConstantState().newDrawable();
//        setImageDrawable(avd);
//        avd.forceAnimationOnUI();
//        avd.start();
        //added by chenzhengjun end
        setContentDescription(getContentDescription(expanded));
    }

    /** Whether the icons are using the default direction or the opposite */
    public void setDefaultDirection(boolean isDefaultDirection) {
        mIsDefaultDirection = isDefaultDirection;
    }

    private int getDrawableResourceId(boolean expanded) {
        //added by chenzhengjun start
        if (mIsDefaultDirection) {
            return expanded ? R.drawable.ic_volume_expand_dowm //ic_volume_collapse_animation
                    : R.drawable.ic_volume_collapse_up;//ic_volume_expand_animation
        } else {
            return expanded ? R.drawable.ic_volume_collapse_up //ic_volume_expand_animation
                    : R.drawable.ic_volume_expand_dowm;//ic_volume_collapse_animation
        }
        //added by chenzhengjun end
    }

    private String getContentDescription(boolean expanded) {
        return expanded ? mContext.getString(R.string.accessibility_quick_settings_collapse)
                : mContext.getString(R.string.accessibility_quick_settings_expand);
    }
}
