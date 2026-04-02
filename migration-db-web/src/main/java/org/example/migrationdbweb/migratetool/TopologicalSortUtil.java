package org.example.migrationdbweb.migratetool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Topological Sort cho View Dependency Graph.
 * Dùng Kahn's Algorithm (BFS) để sắp xếp view theo đúng thứ tự phụ thuộc.
 *
 * Ví dụ:
 * - view_EMP_DEPT phụ thuộc view_EMP và view_DEPT
 * - Thứ tự đúng: [view_DEPT, view_EMP, view_EMP_DEPT]
 *
 * Algorithm:
 * 1. Build directed graph: edge A -> B nghĩa là A phụ thuộc B, B phải tạo TRƯỚC A
 * 2. Tìm tất cả node có in-degree = 0 (không phụ thuộc view nào trong danh sách)
 * 3. BFS: lấy node in-degree = 0, thêm vào result, giảm in-degree của các neighbor
 * 4. Nếu result.size() < total -> có circular dependency
 */
public class TopologicalSortUtil {

    private TopologicalSortUtil() {
        // utility class, không khởi tạo
    }

    /**
     * Sort views theo dependency.
     * View phụ thuộc bảng (không phải view) vẫn xử lý bình thường vì bảng luôn tạo trước view.
     *
     * @param views            Danh sách ViewDefinition cần sort
     * @param dependencyMap    Map: viewName -> Set các view mà nó phụ thuộc
     *                          Ví dụ: {"VIEW_EMP_DEPT": ["VIEW_EMP", "VIEW_DEPT"]}
     * @return Danh sách views đã sort, view không phụ thuộc đứng trước
     * @throws IllegalStateException nếu có circular dependency
     */
    public static List<ViewDefinition> sortByDependency(
            List<ViewDefinition> views,
            Map<String, Set<String>> dependencyMap
    ) {
        if (views == null || views.isEmpty()) {
            return Collections.emptyList();
        }

        // Bước 1: Build lookup map (case-insensitive)
        Map<String, ViewDefinition> viewMap = new HashMap<>();
        for (ViewDefinition view : views) {
            String key = normalize(view.getViewName());
            viewMap.put(key, view);
        }

        // Normalize dependency keys/values to avoid case-sensitivity mismatches
        // from metadata providers (e.g., PostgreSQL lowercase vs Oracle uppercase).
        Map<String, Set<String>> normalizedInputDeps = new HashMap<>();
        if (dependencyMap != null) {
            for (Map.Entry<String, Set<String>> entry : dependencyMap.entrySet()) {
                String viewKey = normalize(entry.getKey());
                if (viewKey.isEmpty()) {
                    continue;
                }

                Set<String> targetDeps = normalizedInputDeps.computeIfAbsent(viewKey, k -> new HashSet<>());
                if (entry.getValue() == null) {
                    continue;
                }

                for (String dep : entry.getValue()) {
                    String depKey = normalize(dep);
                    if (!depKey.isEmpty()) {
                        targetDeps.add(depKey);
                    }
                }
            }
        }

        // Khởi tạo dependency map cho những view không có dependency ghi nhận
        Map<String, Set<String>> normalizedDeps = new HashMap<>();
        for (ViewDefinition view : views) {
            String viewKey = normalize(view.getViewName());
            Set<String> deps = normalizedInputDeps.getOrDefault(viewKey, Collections.emptySet());
            // Chỉ giữ lại dependency nào là VIEW trong danh sách cần migrate
            Set<String> filteredDeps = new HashSet<>();
            for (String dep : deps) {
                String depKey = normalize(dep);
                if (viewMap.containsKey(depKey)) {
                    filteredDeps.add(depKey);
                }
            }
            normalizedDeps.put(viewKey, filteredDeps);
        }

        // Bước 2: Tính in-degree
        // in-degree[X] = số lượng view Y mà X phụ thuộc (Y phải tạo trước X)
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacencyList = new HashMap<>(); // Y -> [các X phụ thuộc Y]

        for (ViewDefinition view : views) {
            String viewKey = normalize(view.getViewName());
            inDegree.put(viewKey, 0);
            adjacencyList.put(viewKey, new ArrayList<>());
        }

        for (Map.Entry<String, Set<String>> entry : normalizedDeps.entrySet()) {
            String viewKey = entry.getKey();
            for (String depKey : entry.getValue()) {
                // viewKey phụ thuộc depKey
                // -> depKey phải tạo TRƯỚC viewKey
                // -> thêm edge: depKey -> viewKey
                adjacencyList.computeIfAbsent(depKey, k -> new ArrayList<>()).add(viewKey);
                // Tăng in-degree của viewKey
                inDegree.put(viewKey, inDegree.getOrDefault(viewKey, 0) + 1);
            }
        }

        // Bước 3: Kahn's Algorithm
        // Queue chứa các view có in-degree = 0 (không phụ thuộc view nào trong danh sách)
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<ViewDefinition> sorted = new ArrayList<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            ViewDefinition viewDef = viewMap.get(current);
            if (viewDef != null) {
                sorted.add(viewDef);
            }

            // Giảm in-degree của các view phụ thuộc current
            for (String neighbor : adjacencyList.getOrDefault(current, Collections.emptyList())) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        // Bước 4: Kiểm tra circular dependency
        if (sorted.size() != views.size()) {
            Set<String> sortedKeys = sorted.stream()
                    .map(v -> normalize(v.getViewName()))
                    .collect(Collectors.toSet());

            List<String> circularViews = views.stream()
                    .map(v -> normalize(v.getViewName()))
                    .filter(key -> !sortedKeys.contains(key))
                    .collect(Collectors.toList());

            throw new IllegalStateException(
                    "Circular dependency detected among views: " + circularViews
                            + ". Cannot resolve view creation order. "
                    + "Please break the circular reference and try again."
            );
        }

        return sorted;
    }

    /**
     * Normalize tên view sang uppercase để so sánh case-insensitive.
     */
    private static String normalize(String name) {
        return name != null ? name.trim().toUpperCase() : "";
    }
}
