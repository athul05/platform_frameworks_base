/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.ActivityTaskManager.RESIZE_MODE_SYSTEM_SCREEN_ROTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.res.Configuration.EMPTY;
import static android.view.SurfaceControl.METADATA_TASK_ID;

import static com.android.server.EventLogTags.WM_TASK_CREATED;
import static com.android.server.EventLogTags.WM_TASK_REMOVED;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.TaskProto.APP_WINDOW_TOKENS;
import static com.android.server.wm.TaskProto.BOUNDS;
import static com.android.server.wm.TaskProto.DISPLAYED_BOUNDS;
import static com.android.server.wm.TaskProto.FILLS_PARENT;
import static com.android.server.wm.TaskProto.ID;
import static com.android.server.wm.TaskProto.SURFACE_HEIGHT;
import static com.android.server.wm.TaskProto.SURFACE_WIDTH;
import static com.android.server.wm.TaskProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.CallSuper;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskDescription;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;

import java.io.PrintWriter;
import java.util.function.Consumer;

class Task extends WindowContainer<ActivityRecord> implements ConfigurationContainerListener{
    static final String TAG = TAG_WITH_CLASS_NAME ? "Task" : TAG_WM;

    final ActivityTaskManagerService mAtmService;

    /* Unique identifier for this task. */
    final int mTaskId;
    /* User for which this task was created. */
    // TODO: Make final
    int mUserId;

    final Rect mPreparedFrozenBounds = new Rect();
    final Configuration mPreparedFrozenMergedConfig = new Configuration();

    // If non-empty, bounds used to display the task during animations/interactions.
    // TODO(b/119687367): This member is temporary.
    private final Rect mOverrideDisplayedBounds = new Rect();

    /** ID of the display which rotation {@link #mRotation} has. */
    private int mLastRotationDisplayId = Display.INVALID_DISPLAY;
    /**
     * Display rotation as of the last time {@link #setBounds(Rect)} was called or this task was
     * moved to a new display.
     */
    private int mRotation;

    // For comparison with DisplayContent bounds.
    private Rect mTmpRect = new Rect();
    // For handling display rotations.
    private Rect mTmpRect2 = new Rect();

    // Resize mode of the task. See {@link ActivityInfo#resizeMode}
    // Based on the {@link ActivityInfo#resizeMode} of the root activity.
    int mResizeMode;

    // Whether or not this task and its activities support PiP. Based on the
    // {@link ActivityInfo#FLAG_SUPPORTS_PICTURE_IN_PICTURE} flag of the root activity.
    boolean mSupportsPictureInPicture;

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;
    private int mDragResizeMode;

    // This represents the last resolved activity values for this task
    // NOTE: This value needs to be persisted with each task
    private TaskDescription mTaskDescription;

    // If set to true, the task will report that it is not in the floating
    // state regardless of it's stack affiliation. As the floating state drives
    // production of content insets this can be used to preserve them across
    // stack moves and we in fact do so when moving from full screen to pinned.
    private boolean mPreserveNonFloatingState = false;

    private Dimmer mDimmer = new Dimmer(this);
    private final Rect mTmpDimBoundsRect = new Rect();

    /** @see #setCanAffectSystemUiFlags */
    private boolean mCanAffectSystemUiFlags = true;

    Task(int taskId, TaskStack stack, int userId, int resizeMode, boolean supportsPictureInPicture,
            TaskDescription taskDescription, ActivityTaskManagerService atm) {
        super(atm.mWindowManager);
        mAtmService = atm;
        mTaskId = taskId;
        mUserId = userId;
        mResizeMode = resizeMode;
        mSupportsPictureInPicture = supportsPictureInPicture;
        mTaskDescription = taskDescription;
        EventLog.writeEvent(WM_TASK_CREATED, mTaskId,
                stack != null ? stack.mStackId : INVALID_STACK_ID);

        // Tasks have no set orientation value (including SCREEN_ORIENTATION_UNSPECIFIED).
        setOrientation(SCREEN_ORIENTATION_UNSET);
        // TODO(task-merge): Is this really needed?
        //setBounds(getResolvedOverrideBounds());
    }

    @Override
    DisplayContent getDisplayContent() {
        return getTaskStack() != null ? getTaskStack().getDisplayContent() : null;
    }

    TaskStack getTaskStack() {
        return (TaskStack) getParent();
    }

    int getAdjustedAddPosition(ActivityRecord r, int suggestedPosition) {
        int maxPosition = mChildren.size();
        if (!r.mTaskOverlay) {
            // We want to place all non-overlay activities below overlays.
            while (maxPosition > 0) {
                final ActivityRecord current = mChildren.get(maxPosition - 1);
                if (current.mTaskOverlay && !current.removed) {
                    --maxPosition;
                    continue;
                }
                break;
            }
            if (maxPosition < 0) {
                maxPosition = 0;
            }
        }

        if (suggestedPosition >= maxPosition) {
            return Math.min(maxPosition, suggestedPosition);
        }

        for (int pos = 0; pos < maxPosition && pos < suggestedPosition; ++pos) {
            // TODO: Confirm that this is the behavior we want long term.
            if (mChildren.get(pos).removed) {
                // suggestedPosition assumes removed tokens are actually gone.
                ++suggestedPosition;
            }
        }
        return Math.min(maxPosition, suggestedPosition);
    }

    @Override
    void positionChildAt(int position, ActivityRecord child, boolean includingParents) {
        position = getAdjustedAddPosition(child, position);
        super.positionChildAt(position, child, includingParents);
    }

    private boolean hasWindowsAlive() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (mChildren.get(i).hasWindowsAlive()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    boolean shouldDeferRemoval() {
        if (mChildren.isEmpty()) {
            // No reason to defer removal of a Task that doesn't have any child.
            return false;
        }
        return hasWindowsAlive() && getTaskStack().isAnimating(TRANSITION | CHILDREN);
    }

    @Override
    void removeIfPossible() {
        if (shouldDeferRemoval()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            return;
        }
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "removeTask");
        super.removeImmediately();
    }

    // TODO: Consolidate this with TaskRecord.reparent()
    void reparent(TaskStack stack, int position, boolean moveParents, String reason) {
        if (DEBUG_STACK) Slog.i(TAG, "reParentTask: removing taskId=" + mTaskId
                + " from stack=" + getTaskStack());
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "reParentTask");

        // TODO(stack-merge): Remove cast.
        final ActivityStack prevStack = (ActivityStack) getTaskStack();
        final boolean wasTopFocusedStack =
                mAtmService.mRootActivityContainer.isTopDisplayFocusedStack(prevStack);
        final ActivityDisplay prevStackDisplay = prevStack.getDisplay();

        position = stack.findPositionForTask(this, position, showForAllUsers());

        reparent(stack, position);

        if (!moveParents) {
            // Only move home stack forward if we are not going to move the new parent forward.
            prevStack.moveHomeStackToFrontIfNeeded(wasTopFocusedStack, prevStackDisplay, reason);
        }

        // TODO(task-merge): Remove cast.
        stack.positionChildAt(position, (TaskRecord) this, moveParents);

        // If we are moving from the fullscreen stack to the pinned stack then we want to preserve
        // our insets so that there will not be a jump in the area covered by system decorations.
        // We rely on the pinned animation to later unset this value.
        mPreserveNonFloatingState = stack.inPinnedWindowingMode();
    }

    void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < mChildren.size(); appTokenNdx++) {
            mChildren.get(appTokenNdx).sendingToBottom = toBottom;
        }
    }

    public int setBounds(Rect bounds, boolean forceResize) {
        final int boundsChanged = setBounds(bounds);

        if (forceResize && (boundsChanged & BOUNDS_CHANGE_SIZE) != BOUNDS_CHANGE_SIZE) {
            onResize();
            return BOUNDS_CHANGE_SIZE | boundsChanged;
        }

        return boundsChanged;
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    @Override
    public int setBounds(Rect bounds) {
        int rotation = Surface.ROTATION_0;
        final DisplayContent displayContent = getTaskStack() != null
                ? getTaskStack().getDisplayContent() : null;
        if (displayContent != null) {
            rotation = displayContent.getDisplayInfo().rotation;
        } else if (bounds == null) {
            return super.setBounds(bounds);
        }

        final int boundsChange = super.setBounds(bounds);

        mRotation = rotation;

        updateSurfacePosition();
        return boundsChange;
    }

    @Override
    public boolean onDescendantOrientationChanged(IBinder freezeDisplayToken,
            ConfigurationContainer requestingContainer) {
        if (super.onDescendantOrientationChanged(freezeDisplayToken, requestingContainer)) {
            return true;
        }

        // No one in higher hierarchy handles this request, let's adjust our bounds to fulfill
        // it if possible.
        if (getParent() != null) {
            onConfigurationChanged(getParent().getConfiguration());
            return true;
        }
        return false;
    }

    void resize(boolean relayout, boolean forced) {
        if (setBounds(getRequestedOverrideBounds(), forced) != BOUNDS_CHANGE_NONE && relayout) {
            getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        adjustBoundsForDisplayChangeIfNeeded(dc);
        super.onDisplayChanged(dc);
        final int displayId = (dc != null) ? dc.getDisplayId() : Display.INVALID_DISPLAY;
        mWmService.mAtmService.getTaskChangeNotificationController().notifyTaskDisplayChanged(
                mTaskId, displayId);
    }

    /**
     * Displayed bounds are used to set where the task is drawn at any given time. This is
     * separate from its actual bounds so that the app doesn't see any meaningful configuration
     * changes during transitionary periods.
     */
    void setOverrideDisplayedBounds(Rect overrideDisplayedBounds) {
        if (overrideDisplayedBounds != null) {
            mOverrideDisplayedBounds.set(overrideDisplayedBounds);
        } else {
            mOverrideDisplayedBounds.setEmpty();
        }
        updateSurfacePosition();
    }

    /**
     * Gets the bounds that override where the task is displayed. See
     * {@link android.app.IActivityTaskManager#resizeDockedStack} why this is needed.
     */
    Rect getOverrideDisplayedBounds() {
        return mOverrideDisplayedBounds;
    }

    boolean isResizeable(boolean checkSupportsPip) {
        return (mAtmService.mForceResizableActivities || ActivityInfo.isResizeableMode(mResizeMode)
                || (checkSupportsPip && mSupportsPictureInPicture));
    }

    boolean isResizeable() {
        return isResizeable(true /* checkSupportsPip */);
    }

    /**
     * Tests if the orientation should be preserved upon user interactive resizig operations.

     * @return true if orientation should not get changed upon resizing operation.
     */
    boolean preserveOrientationOnResize() {
        return mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
    }

    boolean cropWindowsToStackBounds() {
        return isResizeable();
    }

    /**
     * Prepares the task bounds to be frozen with the current size. See
     * {@link ActivityRecord#freezeBounds}.
     */
    void prepareFreezingBounds() {
        mPreparedFrozenBounds.set(getBounds());
        mPreparedFrozenMergedConfig.setTo(getConfiguration());
    }

    /**
     * Align the task to the adjusted bounds.
     *
     * @param adjustedBounds Adjusted bounds to which the task should be aligned.
     * @param tempInsetBounds Insets bounds for the task.
     * @param alignBottom True if the task's bottom should be aligned to the adjusted
     *                    bounds's bottom; false if the task's top should be aligned
     *                    the adjusted bounds's top.
     */
    void alignToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds, boolean alignBottom) {
        if (!isResizeable() || EMPTY.equals(getRequestedOverrideConfiguration())) {
            return;
        }

        getBounds(mTmpRect2);
        if (alignBottom) {
            int offsetY = adjustedBounds.bottom - mTmpRect2.bottom;
            mTmpRect2.offset(0, offsetY);
        } else {
            mTmpRect2.offsetTo(adjustedBounds.left, adjustedBounds.top);
        }
        if (tempInsetBounds == null || tempInsetBounds.isEmpty()) {
            setOverrideDisplayedBounds(null);
            setBounds(mTmpRect2);
        } else {
            setOverrideDisplayedBounds(mTmpRect2);
            setBounds(tempInsetBounds);
        }
    }

    /**
     * Gets the current overridden displayed bounds. These will be empty if the task is not
     * currently overriding where it is displayed.
     */
    @Override
    public Rect getDisplayedBounds() {
        if (mOverrideDisplayedBounds.isEmpty()) {
            return super.getDisplayedBounds();
        } else {
            return mOverrideDisplayedBounds;
        }
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final WindowState windowState = getTopVisibleAppMainWindow();
        if (windowState != null) {
            windowState.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        } else {
            super.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        }
    }

    /**
     * Calculate the maximum visible area of this task. If the task has only one app,
     * the result will be visible frame of that app. If the task has more than one apps,
     * we search from top down if the next app got different visible area.
     *
     * This effort is to handle the case where some task (eg. GMail composer) might pop up
     * a dialog that's different in size from the activity below, in which case we should
     * be dimming the entire task area behind the dialog.
     *
     * @param out Rect containing the max visible bounds.
     * @return true if the task has some visible app windows; false otherwise.
     */
    private boolean getMaxVisibleBounds(Rect out) {
        boolean foundTop = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (token.mIsExiting || token.isClientHidden() || token.hiddenRequested) {
                continue;
            }
            final WindowState win = token.findMainWindow();
            if (win == null) {
                continue;
            }
            if (!foundTop) {
                foundTop = true;
                out.setEmpty();
            }

            win.getMaxVisibleBounds(out);
        }
        return foundTop;
    }

    /** Bounds of the task to be used for dimming, as well as touch related tests. */
    public void getDimBounds(Rect out) {
        final DisplayContent displayContent = getTaskStack().getDisplayContent();
        // It doesn't matter if we in particular are part of the resize, since we couldn't have
        // a DimLayer anyway if we weren't visible.
        final boolean dockedResizing = displayContent != null
                && displayContent.mDividerControllerLocked.isResizing();
        if (inFreeformWindowingMode() && getMaxVisibleBounds(out)) {
            return;
        }

        if (!matchParentBounds()) {
            // When minimizing the docked stack when going home, we don't adjust the task bounds
            // so we need to intersect the task bounds with the stack bounds here.
            //
            // If we are Docked Resizing with snap points, the task bounds could be smaller than the
            // stack bounds and so we don't even want to use them. Even if the app should not be
            // resized the Dim should keep up with the divider.
            if (dockedResizing) {
                getTaskStack().getBounds(out);
            } else {
                getTaskStack().getBounds(mTmpRect);
                mTmpRect.intersect(getBounds());
                out.set(mTmpRect);
            }
        } else {
            out.set(getBounds());
        }
        return;
    }

    void setDragResizing(boolean dragResizing, int dragResizeMode) {
        if (mDragResizing != dragResizing) {
            // No need to check if the mode is allowed if it's leaving dragResize
            if (dragResizing && !DragResizeMode.isModeAllowedForStack(getTaskStack(), dragResizeMode)) {
                throw new IllegalArgumentException("Drag resize mode not allow for stack stackId="
                        + getTaskStack().mStackId + " dragResizeMode=" + dragResizeMode);
            }
            mDragResizing = dragResizing;
            mDragResizeMode = dragResizeMode;
            resetDragResizingChangeReported();
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    int getDragResizeMode() {
        return mDragResizeMode;
    }

    /**
     * Puts this task into docked drag resizing mode. See {@link DragResizeMode}.
     *
     * @param resizing Whether to put the task into drag resize mode.
     */
    public void setTaskDockedResizing(boolean resizing) {
        setDragResizing(resizing, DRAG_RESIZE_MODE_DOCKED_DIVIDER);
    }

    void adjustBoundsForDisplayChangeIfNeeded(final DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        if (matchParentBounds()) {
            // TODO: Yeah...not sure if this works with WindowConfiguration, but shouldn't be a
            // problem once we move mBounds into WindowConfiguration.
            setBounds(null);
            return;
        }
        final int displayId = displayContent.getDisplayId();
        final int newRotation = displayContent.getDisplayInfo().rotation;
        if (displayId != mLastRotationDisplayId) {
            // This task is on a display that it wasn't on. There is no point to keep the relative
            // position if display rotations for old and new displays are different. Just keep these
            // values.
            mLastRotationDisplayId = displayId;
            mRotation = newRotation;
            return;
        }

        if (mRotation == newRotation) {
            // Rotation didn't change. We don't need to adjust the bounds to keep the relative
            // position.
            return;
        }

        // Device rotation changed.
        // - We don't want the task to move around on the screen when this happens, so update the
        //   task bounds so it stays in the same place.
        // - Rotate the bounds and notify activity manager if the task can be resized independently
        //   from its stack. The stack will take care of task rotation for the other case.
        mTmpRect2.set(getBounds());

        if (!getWindowConfiguration().canResizeTask()) {
            setBounds(mTmpRect2);
            return;
        }

        displayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (setBounds(mTmpRect2) != BOUNDS_CHANGE_NONE) {
            mAtmService.resizeTask(mTaskId, getBounds(), RESIZE_MODE_SYSTEM_SCREEN_ROTATION);
        }
    }

    /** Cancels any running app transitions associated with the task. */
    void cancelTaskWindowTransition() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).cancelAnimation();
        }
    }

    boolean showForAllUsers() {
        final int tokensCount = mChildren.size();
        return (tokensCount != 0) && mChildren.get(tokensCount - 1).mShowForAllUsers;
    }

    /**
     * When we are in a floating stack (Freeform, Pinned, ...) we calculate
     * insets differently. However if we are animating to the fullscreen stack
     * we need to begin calculating insets as if we were fullscreen, otherwise
     * we will have a jump at the end.
     */
    boolean isFloating() {
        return getWindowConfiguration().tasksAreFloating()
                && !getTaskStack().isAnimatingBoundsToFullscreen() && !mPreserveNonFloatingState;
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        if (WindowManagerService.sHierarchicalAnimations) {
            return super.getAnimationLeashParent();
        }
        // Currently, only the recents animation will create animation leashes for tasks. In this
        // case, reparent the task to the home animation layer while it is being animated to allow
        // the home activity to reorder the app windows relative to its own.
        return getAppAnimationLayer(ANIMATION_LAYER_HOME);
    }

    boolean shouldAnimate() {
        // Don't animate while the task runs recents animation but only if we are in the mode
        // where we cancel with deferred screenshot, which means that the controller has
        // transformed the task.
        final RecentsAnimationController controller = mWmService.getRecentsAnimationController();
        if (controller != null && controller.isAnimatingTask(this)
                && controller.shouldDeferCancelUntilNextTransition()) {
            return false;
        }
        return true;
    }

    @Override
    SurfaceControl.Builder makeSurface() {
        return super.makeSurface().setMetadata(METADATA_TASK_ID, mTaskId);
    }

    boolean isTaskAnimating() {
        final RecentsAnimationController recentsAnim = mWmService.getRecentsAnimationController();
        if (recentsAnim != null) {
            if (recentsAnim.isAnimatingTask(this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if changing app transition is running.
     */
    @Override
    boolean isChangingAppTransition() {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null && getDisplayContent().mChangingApps.contains(activity);
    }

    @Override
    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null ? activity.createRemoteAnimationTarget(record) : null;
    }

    WindowState getTopVisibleAppMainWindow() {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null ? activity.findMainWindow() : null;
    }

    ActivityRecord getTopFullscreenActivity() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mChildren.get(i);
            final WindowState win = activity.findMainWindow();
            if (win != null && win.mAttrs.isFullscreen()) {
                return activity;
            }
        }
        return null;
    }

    ActivityRecord getTopVisibleActivity() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (!token.mIsExiting && !token.isClientHidden() && !token.hiddenRequested) {
                return token;
            }
        }
        return null;
    }

    void positionChildAtTop(ActivityRecord child) {
        positionChildAt(child, POSITION_TOP);
    }

    void positionChildAt(ActivityRecord child, int position) {
        if (child == null) {
            Slog.w(TAG_WM,
                    "Attempted to position of non-existing app");
            return;
        }

        positionChildAt(position, child, false /* includeParents */);
    }

    void forceWindowsScaleable(boolean force) {
        mWmService.openSurfaceTransaction();
        try {
            for (int i = mChildren.size() - 1; i >= 0; i--) {
                mChildren.get(i).forceWindowsScaleableInTransaction(force);
            }
        } finally {
            mWmService.closeSurfaceTransaction("forceWindowsScaleable");
        }
    }

    void setTaskDescription(TaskDescription taskDescription) {
        mTaskDescription = taskDescription;
    }

    void onSnapshotChanged(ActivityManager.TaskSnapshot snapshot) {
        mAtmService.getTaskChangeNotificationController().notifyTaskSnapshotChanged(
                mTaskId, snapshot);
    }

    TaskDescription getTaskDescription() {
        return mTaskDescription;
    }

    @Override
    boolean fillsParent() {
        return matchParentBounds() || !getWindowConfiguration().canResizeTask();
    }

    @Override
    void forAllTasks(Consumer<Task> callback) {
        callback.accept(this);
    }

    @Override
    boolean forAllTasks(ToBooleanFunction<Task> callback) {
        return callback.apply(this);
    }

    /**
     * @param canAffectSystemUiFlags If false, all windows in this task can not affect SystemUI
     *                               flags. See {@link WindowState#canAffectSystemUiFlags()}.
     */
    void setCanAffectSystemUiFlags(boolean canAffectSystemUiFlags) {
        mCanAffectSystemUiFlags = canAffectSystemUiFlags;
    }

    /**
     * @see #setCanAffectSystemUiFlags
     */
    boolean canAffectSystemUiFlags() {
        return mCanAffectSystemUiFlags;
    }

    void dontAnimateDimExit() {
        mDimmer.dontAnimateExit();
    }

    String getName() {
        return toShortString();
    }

    void clearPreserveNonFloatingState() {
        mPreserveNonFloatingState = false;
    }

    @Override
    Dimmer getDimmer() {
        return mDimmer;
    }

    @Override
    void prepareSurfaces() {
        mDimmer.resetDimStates();
        super.prepareSurfaces();
        getDimBounds(mTmpDimBoundsRect);

        // Bounds need to be relative, as the dim layer is a child.
        mTmpDimBoundsRect.offsetTo(0, 0);
        if (mDimmer.updateDims(getPendingTransaction(), mTmpDimBoundsRect)) {
            scheduleAnimation();
        }
    }

    // TODO(proto-merge): Remove once protos for TaskRecord and Task are merged.
    void writeToProtoInnerTaskOnly(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, logLevel);
        proto.write(ID, mTaskId);
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mChildren.get(i);
            activity.writeToProto(proto, APP_WINDOW_TOKENS, logLevel);
        }
        proto.write(FILLS_PARENT, matchParentBounds());
        getBounds().writeToProto(proto, BOUNDS);
        mOverrideDisplayedBounds.writeToProto(proto, DISPLAYED_BOUNDS);
        if (mSurfaceControl != null) {
            proto.write(SURFACE_WIDTH, mSurfaceControl.getWidth());
            proto.write(SURFACE_HEIGHT, mSurfaceControl.getHeight());
        }
        proto.end(token);
    }

    @Override
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        final String doublePrefix = prefix + "  ";

        pw.println(prefix + "taskId=" + mTaskId);
        pw.println(doublePrefix + "mBounds=" + getBounds().toShortString());
        pw.println(doublePrefix + "appTokens=" + mChildren);
        pw.println(doublePrefix + "mDisplayedBounds=" + mOverrideDisplayedBounds.toShortString());

        final String triplePrefix = doublePrefix + "  ";
        final String quadruplePrefix = triplePrefix + "  ";

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mChildren.get(i);
            pw.println(triplePrefix + "Activity #" + i + " " + activity);
            activity.dump(pw, quadruplePrefix, dumpAll);
        }
    }

    String toShortString() {
        return "Task=" + mTaskId;
    }
}
