<script setup lang="ts">
import { ref } from "vue";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import type { FeedItem } from "@/api/types";

const toast = useToast();
const query = ref("");
const results = ref<FeedItem[]>([]);
const isLoading = ref(false);

async function search() {
  isLoading.value = true;
  try {
    const tags = query.value.split(/\s+/).map((tag) => tag.replace(/^#/, "").trim().toLowerCase()).filter(Boolean);
    results.value = await ContentService.feed(tags, 20);
  } catch (error) {
    toast.add({ severity: "error", summary: "Search", detail: error instanceof Error ? error.message : "Unable to search", life: 5000 });
  } finally {
    isLoading.value = false;
  }
}
</script>

<template>
  <main class="search-shell">
    <nav class="search-nav">
      <a href="/" title="Feed" aria-label="Feed"><i class="pi pi-th-large"></i></a>
      <a href="/u/me" title="Profile" aria-label="Profile"><i class="pi pi-user"></i></a>
    </nav>
    <section class="search-panel">
      <div class="search-box">
        <i class="pi pi-search"></i>
        <input v-model="query" type="search" placeholder="Search posts by tags or text" @keyup.enter="search" />
        <button type="button" @click="search">Search</button>
      </div>
      <div v-if="isLoading" class="search-state">Searching</div>
      <article v-for="item in results" :key="item.post.id" class="result-row">
        <strong>{{ item.post.title || "Post" }}</strong>
        <p>{{ item.post.text }}</p>
        <footer>
          <span v-for="tag in item.post.tags" :key="tag">#{{ tag }}</span>
        </footer>
      </article>
    </section>
  </main>
</template>

<style scoped>
.search-shell {
  min-height: 100dvh;
  background: var(--bg);
  color: var(--text);
}

.search-nav {
  position: fixed;
  left: 16px;
  top: 16px;
  display: flex;
  gap: 8px;
}

.search-nav a {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--text);
  background: var(--surface-raised);
  border: 1px solid var(--surface-active);
  text-decoration: none;
}

.search-panel {
  width: min(860px, calc(100% - 32px));
  margin: 0 auto;
  padding: 82px 0 40px;
  display: grid;
  gap: 12px;
}

.search-box {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: 8px;
  border: 1px solid var(--surface-active);
  background: var(--surface-raised);
}

.search-box input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font-size: 16px;
}

.search-box button {
  border: 0;
  border-radius: 8px;
  padding: 10px 14px;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font-weight: 900;
  cursor: pointer;
}

.search-state {
  color: var(--muted);
  font-weight: 800;
  padding: 16px;
}

.result-row {
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--surface-active);
  border-radius: 8px;
  background: var(--surface-raised);
}

.result-row p {
  margin: 0;
  overflow-wrap: anywhere;
}

.result-row footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}
</style>
