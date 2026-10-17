package cn.run.freepocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Completes the paginated read before publishing any negative ownership result. */
final class EpicEntitlements {
    static final int PAGE_SIZE = 1000;
    static final int MAX_PAGES = 100;

    static final class Grant {
        final String namespace;
        final String id;
        final boolean active;
        Grant(String namespace, String id, boolean active) {
            this.namespace = namespace;
            this.id = id;
            this.active = active;
        }
    }

    interface PageSource { List<Grant> read(int start, int count) throws IOException; }

    static List<Grant> readAll(PageSource source) throws IOException {
        Map<String, Grant> owned = new LinkedHashMap<>();
        long startedAt = System.nanoTime();
        for (int page = 0; page < MAX_PAGES; page++) {
            if (System.nanoTime() - startedAt > 120_000_000_000L) throw new IOException("拥有凭证同步超时，未发布不完整结果");
            List<Grant> batch = source.read(page * PAGE_SIZE, PAGE_SIZE);
            if (batch.size() > PAGE_SIZE) throw new IOException("拥有凭证分页格式已变化，未发布不完整结果");
            for (Grant grant : batch) {
                if (grant.active && !grant.namespace.isEmpty() && !grant.id.isEmpty()) owned.put(grant.namespace + ":" + grant.id, grant);
            }
            if (batch.size() < PAGE_SIZE) return new ArrayList<>(owned.values());
        }
        throw new IOException("拥有凭证分页未读取完整，未将缺失项目判为未拥有");
    }
}
