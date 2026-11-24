package com.example.sensorae.ui.d1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** D-1 owns search and details; D-2 supplies only map content and a selection target. */
public final class DestinationHomeView extends LinearLayout {
    private static final int INK = Color.rgb(26, 46, 36), MUTED = Color.rgb(94, 108, 100);
    private static final int GREEN = Color.rgb(24, 78, 53), PALE = Color.rgb(237, 245, 239);
    private final DestinationSearchController controller = new DestinationSearchController(DemoDestinations.create());
    private final FrameLayout mapHost;
    private final LinearLayout sheet;
    private final TextView modeLabel, mapLabel;
    private final EditText search;
    private boolean restoring;
    private LinearLayout sheetContainer;
    private TextView sheetHandle;
    private boolean sheetExpanded;
    private float dragStartY;
    private int dragStartHeight;
    private Runnable routeAction;
    private String routeMessage = "현재 위치에서 길찾기";
    private boolean routeEnabled = true;
    private final TextView locationStatus;
    public void setRouteAction(Runnable action) { routeAction = action; renderSheet(); }
    public void setRouteStatus(String message, boolean enabled) {
        if (routeMessage.equals(message) && routeEnabled == enabled) return;
        routeMessage = message; routeEnabled = enabled; renderSheet();
    }
    public void setLocationStatus(String message) { locationStatus.setText(message); }
    public void setSearchAction(Runnable action) {
        search.setFocusable(false);
        search.setOnClickListener(view -> action.run());
    }


    public DestinationHomeView(Context context, Runnable openCollection) {
        super(context);
        setOrientation(VERTICAL); setBackgroundColor(Color.rgb(247, 249, 247));

        LinearLayout header = row(); header.setPadding(dp(16), dp(6), dp(12), dp(2));
        LinearLayout heading = column();
        TextView name = text("충북대 캠퍼스", 20, INK); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(name);
        modeLabel = text("시험 모드 · 목적지 예시", 12, GREEN); heading.addView(modeLabel);
        header.addView(heading, new LayoutParams(0, -2, 1));
        addView(header);

        LinearLayout searchBox = row();
        searchBox.setPadding(dp(12), dp(2), dp(4), dp(2)); searchBox.setBackground(background(Color.WHITE, 18));
        TextView searchIcon = text("⌕", 24, MUTED); searchIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBox.addView(searchIcon, new LayoutParams(dp(30), -2));
        search = new EditText(context); search.setSingleLine(true); search.setTextSize(16); search.setTextColor(INK);
        search.setHint("어디로 갈까요?"); search.setHintTextColor(MUTED); search.setBackgroundColor(Color.TRANSPARENT);
        search.setContentDescription("건물 이름 검색"); search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search, new LayoutParams(0, dp(50), 1));
        LayoutParams searchParams = new LayoutParams(-1, -2); searchParams.setMargins(dp(16), dp(4), dp(16), dp(4));
        addView(searchBox, searchParams);

        locationStatus = text("현재 위치 수신 대기", 12, MUTED); locationStatus.setPadding(dp(20), 0, dp(20), dp(8)); addView(locationStatus);

        mapHost = new FrameLayout(context);
        mapHost.setContentDescription("개신캠퍼스 지도");
        LinearLayout placeholder = column(); placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(24), dp(12), dp(24), dp(12)); placeholder.setBackgroundColor(Color.rgb(234, 239, 234));
        TextView mapTitle = text("지도 표시 영역", 20, MUTED); mapTitle.setGravity(Gravity.CENTER); placeholder.addView(mapTitle);
        mapLabel = text("목적지를 선택해보세요", 15, INK); mapLabel.setGravity(Gravity.CENTER);
        LayoutParams labelParams = new LayoutParams(-1, -2); labelParams.topMargin = dp(12); placeholder.addView(mapLabel, labelParams);
        TextView pending = text("지금은 검색·선택 화면을 확인하는 단계예요.", 12, MUTED); pending.setGravity(Gravity.CENTER);
        LayoutParams pendingParams = new LayoutParams(-1, -2); pendingParams.topMargin = dp(8); placeholder.addView(pending, pendingParams);
        mapHost.addView(placeholder, new FrameLayout.LayoutParams(-1, -1)); addView(mapHost, new LayoutParams(-1, 0, 1));
        controller.setTarget(new DestinationSelectionTarget() {
            public void onDestinationSelected(Destination d) { mapLabel.setText(d.name + " 선택됨\n지도 연결 전"); }
            public void onSelectionCleared() { mapLabel.setText("목적지를 선택해보세요"); }
        });

        sheetContainer = column();
        sheetContainer.setBackground(background(Color.WHITE, 24));
        sheetHandle = text("━━   목적지 선택   ⌃", 14, MUTED);
        sheetHandle.setGravity(Gravity.CENTER);
        sheetHandle.setContentDescription("목적지 목록 펼치기");
        sheetHandle.setOnClickListener(v -> setSheetExpanded(!sheetExpanded));
        sheetHandle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragStartY = event.getRawY(); dragStartHeight = sheetContainer.getHeight();
                    v.getParent().requestDisallowInterceptTouchEvent(true); return true;
                case MotionEvent.ACTION_MOVE:
                    int height = Math.max(dp(100), Math.min(expandedSheetHeight(),
                        dragStartHeight + Math.round(dragStartY - event.getRawY())));
                    ViewGroup.LayoutParams params = sheetContainer.getLayoutParams();
                    params.height = height; sheetContainer.setLayoutParams(params); return true;
                case MotionEvent.ACTION_UP:
                    if (Math.abs(event.getRawY() - dragStartY) < dp(8)) v.performClick();
                    else setSheetExpanded(event.getRawY() < dragStartY);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setSheetExpanded(sheetExpanded); return true;
                default: return false;
            }
        });
        sheetContainer.addView(sheetHandle, new LayoutParams(-1, dp(48)));
        ScrollView sheetScroll = new ScrollView(context);
        sheet = column(); sheet.setPadding(dp(16), 0, dp(16), dp(12)); sheetScroll.addView(sheet);
        sheetContainer.addView(sheetScroll, new LayoutParams(-1, 0, 1));
        addView(sheetContainer, new LayoutParams(-1, dp(100)));
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (b-t != ob-ot && sheetExpanded) setSheetExpanded(true);
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!restoring) { controller.setQuery(value.toString()); renderSheet(); }
            }
            public void afterTextChanged(Editable value) {}
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); return true; }
            return false;
        });
        renderSheet();
    }

    private int expandedSheetHeight() { return Math.max(dp(100), Math.round(getHeight() * .55f)); }
    private void setSheetExpanded(boolean expanded) {
        sheetExpanded = expanded;
        if (sheetContainer == null) return;
        ViewGroup.LayoutParams params = sheetContainer.getLayoutParams();
        if (params != null) { params.height = expanded ? expandedSheetHeight() : dp(100); sheetContainer.setLayoutParams(params); }
        sheetHandle.setText(expanded ? "━━   목적지 선택   ⌄" : "━━   목적지 선택   ⌃");
        sheetHandle.setContentDescription(expanded ? "목적지 목록 접기" : "목적지 목록 펼치기");
    }

    /** UI thread only. The map must be unattached; attaching replays current selection. */
    public void attachMap(View map, DestinationSelectionTarget target) {
        if (map == null || target == null || map.getParent() != null)
            throw new IllegalArgumentException("An unattached map and target are required");
        mapHost.removeAllViews(); mapHost.addView(map, new FrameLayout.LayoutParams(-1, -1));
        controller.setTarget(target);
    }

    /** C supplies a verified catalog here. A new catalog invalidates a missing selection. */
    public void setDestinations(List<Destination> values) { controller.setDestinations(values); renderSheet(); }

    /** D-2 calls this when a registered building is tapped on the map. */
    public boolean selectDestination(String id) {
        if (!controller.select(id)) return false;
        hideKeyboard(); search.clearFocus(); setSheetExpanded(true); renderSheet(); return true;
    }

    public void saveSelection(Bundle state) {
        state.putBoolean("d1.sheetExpanded", sheetExpanded);
        state.putString("d1.query", controller.query());
        state.putString("d1.selected", controller.selected() == null ? null : controller.selected().id);
    }

    public void restoreSelection(Bundle state) {
        if (state == null) return;
        setSheetExpanded(state.getBoolean("d1.sheetExpanded", false));
        restoring = true; search.setText(state.getString("d1.query", "")); restoring = false;
        controller.restore(search.getText().toString(), state.getString("d1.selected")); renderSheet();
    }

    private void renderSheet() {
        if (sheet == null) return;
        boolean demo = false; for (Destination d : controller.destinations()) if (d.isDemo) demo = true;
        modeLabel.setText(demo ? "시험 모드 · 목적지 예시" : "개신캠퍼스 · OSM 보행로 / 현장 확인 전");
        sheet.removeAllViews();
        Destination selected = controller.selected();
        if (selected != null) {
            LinearLayout titleRow = row();
            TextView title = text(selected.name, 18, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            titleRow.addView(title, new LayoutParams(0, -2, 1));
            titleRow.addView(button("선택 취소", () -> { controller.clearSelection(); renderSheet(); }), new LayoutParams(-2, dp(48)));
            sheet.addView(titleRow);
            String meta = selected.category + (selected.isDemo ? " · 시험용 목적지" : "");
            addSheetText(meta, 13, GREEN, 2);
            addSheetText(selected.description, 14, MUTED, 10);
            if (!selected.hasCoordinates()) addSheetText("실제 지도 좌표는 아직 연결되지 않았어요.", 13, MUTED, 8);
            Button route = button(routeMessage, () -> { if (routeAction != null) routeAction.run(); }); route.setEnabled(routeAction != null && routeEnabled);
            route.setBackground(background(PALE, 12)); route.setTextColor(MUTED);
            LayoutParams routeParams = new LayoutParams(-1, dp(52)); routeParams.topMargin = dp(14); sheet.addView(route, routeParams);
            return;
        }
        List<Destination> results = controller.results();
        String heading = controller.query().trim().isEmpty() ? "목적지 선택" : "검색 결과 " + results.size() + "개";
        if (!controller.query().trim().isEmpty()) { TextView title = text(heading, 18, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); sheet.addView(title); }
        if (results.isEmpty()) {
            addSheetText("검색 결과가 없어요. 다른 건물 이름을 입력해보세요.", 14, MUTED, 14);
            return;
        }
        for (Destination d : results) {
            Button item = button(d.name + "   ›", () -> selectDestination(d.id));
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); item.setTextSize(16);
            item.setPadding(dp(14), 0, dp(14), 0); item.setBackground(background(Color.rgb(246, 248, 246), 10));
            item.setContentDescription(d.name + " 목적지 선택" + (d.isDemo ? ", 시험 자료" : ""));
            LayoutParams itemParams = new LayoutParams(-1, dp(48)); itemParams.topMargin = dp(4); sheet.addView(item, itemParams);
        }
    }

    private void addSheetText(String value, int size, int color, int top) {
        TextView view = text(value, size, color); LayoutParams p = new LayoutParams(-1, -2); p.topMargin = dp(top); sheet.addView(view, p);
    }
    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }
    private LinearLayout row() { LinearLayout view = new LinearLayout(getContext()); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(VERTICAL); return view; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String value, Runnable action) {
        Button view = new Button(getContext()); view.setText(value); view.setTextColor(GREEN); view.setAllCaps(false);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
