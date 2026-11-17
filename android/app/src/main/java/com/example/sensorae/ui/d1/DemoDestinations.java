package com.example.sensorae.ui.d1;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** UI fixtures only; not a verified campus directory or geocoded destination dataset. */
public final class DemoDestinations {
    private DemoDestinations() {}
    public static List<Destination> create() {
        return Collections.unmodifiableList(Arrays.asList(
            demo("library", "도서관", "학습 공간", "책을 읽고 공부할 장소를 선택하는 화면 예시입니다.", "중앙도서관", "library"),
            demo("student-center", "학생회관", "학생 시설", "학생 시설을 검색하고 선택하는 화면 예시입니다.", "학생 회관"),
            demo("administration", "대학본부", "대학 시설", "대학 시설을 선택하는 화면 예시입니다.", "본부"),
            demo("main-gate", "정문", "출입 지점", "출입 지점을 목적지로 선택하는 화면 예시입니다.", "정 문"),
            demo("engineering", "공학관", "교육 시설", "교육 시설을 검색하고 선택하는 화면 예시입니다.", "공학"),
            demo("gym", "체육관", "체육 시설", "체육 시설을 검색하고 선택하는 화면 예시입니다.", "체육")
        ));
    }
    private static Destination demo(String id, String name, String category, String description, String... aliases) {
        return new Destination("demo-" + id, name, category, description, null, null, true, Arrays.asList(aliases));
    }
}
