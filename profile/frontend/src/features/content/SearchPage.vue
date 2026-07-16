<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import { contentUrl, isContentPath } from "@/api/navigation";
import { displayUsername, postSnippet } from "@/features/display/displayText";
import { embedQuery, postEmbedNavigation, withEmbedQuery } from "@/features/embed/profileEmbed";
import type { FeedItem, SearchFacet, SearchItem, SearchProviderStatus, SearchSuggestion } from "@/api/types";

type SearchTypeFilter = "posts" | "collections" | "tags";
type SortMode = "relevance" | "new" | "popular";
type DateRange = "" | "day" | "week" | "month";

interface SearchHistoryItem {
  query: string;
  types: SearchTypeFilter[];
  providers: string[];
  tags: string[];
  author?: string;
  dateRange?: DateRange;
  sort: SortMode;
  searchedAt: string;
}

const SEARCH_HISTORY_KEY = "onix.search.history";
const SEARCH_FILTERS_KEY = "onix.search.filters";

const router = useRouter();
const route = useRoute();
const toast = useToast();

const query = ref("");
const selectedTypes = ref<SearchTypeFilter[]>([]);
const selectedProviders = ref<string[]>([]);
const selectedTags = ref<string[]>([]);
const selectedAuthor = ref("");
const selectedDateRange = ref<DateRange>("");
const sortMode = ref<SortMode>("relevance");
const results = ref<SearchItem[]>([]);
const facets = ref<SearchFacet[]>([]);
const providerStatuses = ref<SearchProviderStatus[]>([]);
const partialErrors = ref<string[]>([]);
const suggestions = ref<SearchSuggestion[]>([]);
const history = ref<SearchHistoryItem[]>([]);
const discoverItems = ref<FeedItem[]>([]);
const nextCursor = ref<string | null>(null);
const activeCursor = ref<string | null>(null);
const hasSearched = ref(false);
const isSearchFocused = ref(false);
const isLoading = ref(false);
const isSuggesting = ref(false);
const filtersOpen = ref(false);
let suggestTimer: number | undefined;

const sortFilters: Array<{ id: SortMode; label: string; icon: string }> = [
  { id: "relevance", label: "Best", icon: "pi pi-sparkles" },
  { id: "new", label: "Newest", icon: "pi pi-clock" },
  { id: "popular", label: "Popular", icon: "pi pi-heart" },
];

const dateFacetFallback: SearchFacet[] = [
  { group: "dateRange", value: "day", label: "Past day", count: 0, selected: false },
  { group: "dateRange", value: "week", label: "Past week", count: 0, selected: false },
  { group: "dateRange", value: "month", label: "Past month", count: 0, selected: false },
];

const visibleHistory = computed(() => {
  const needle = query.value.trim().toLowerCase();
  return history.value
    .filter((item) => !needle || item.query.toLowerCase().includes(needle))
    .slice(0, 6);
});

const facetGroups = computed(() => {
  const groups = [
    { key: "type", title: "Content type", icon: "pi pi-th-large" },
    { key: "provider", title: "Source", icon: "pi pi-database" },
    { key: "tag", title: "Tags", icon: "pi pi-hashtag" },
    { key: "owner", title: "Owners", icon: "pi pi-user" },
  ].map((group) => ({
    ...group,
    items: facets.value.filter((facet) => facet.group === group.key).slice(0, group.key === "tag" ? 10 : 8),
  }));
  const dates = facets.value.filter((facet) => facet.group === "dateRange");
  groups.push({
    key: "dateRange",
    title: "Date",
    icon: "pi pi-calendar",
    items: (dates.length ? dates : dateFacetFallback).map((facet) => ({
      ...facet,
      selected: selectedDateRange.value === facet.value,
    })),
  });
  return groups.filter((group) => group.items.length > 0);
});

const activeFilterCount = computed(() => (
  selectedTypes.value.length
  + selectedProviders.value.length
  + selectedTags.value.length
  + (selectedAuthor.value ? 1 : 0)
  + (selectedDateRange.value ? 1 : 0)
));

const degradedStatuses = computed(() => providerStatuses.value.filter((status) => status.status !== "ok"));
const showHistory = computed(() => isSearchFocused.value && !query.value.trim() && visibleHistory.value.length > 0);
const showSuggestions = computed(() => isSearchFocused.value && query.value.trim().length > 0 && suggestions.value.length > 0);
const showDiscover = computed(() => !hasSearched.value && !query.value.trim() && results.value.length === 0);

onMounted(() => {
  history.value = loadHistory();
  restoreFilters();
  applyRouteState();
  void loadDiscover();
  if (query.value.trim() || activeFilterCount.value > 0) void search(false);
});

watch(query, () => {
  window.clearTimeout(suggestTimer);
  const text = query.value.trim();
  if (!text) {
    suggestions.value = [];
    return;
  }
  suggestTimer = window.setTimeout(() => void loadSuggestions(text), 180);
});

async function loadDiscover() {
  try {
    const seed = `search-${new Date().toISOString().slice(0, 10)}`;
    const feed = await ContentService.recommendationFeed({ chunkX: 0, chunkY: 0, sessionSeed: seed, limit: 8 });
    discoverItems.value = feed.items;
  } catch {
    discoverItems.value = [];
  }
}

async function loadSuggestions(text: string) {
  isSuggesting.value = true;
  try {
    const response = await ProfileService.searchSuggest(text, 8);
    const local = visibleHistory.value.map((item) => ({
      type: "RECENT" as const,
      value: item.query,
      label: item.query,
    }));
    suggestions.value = [...local, ...response.suggestions]
      .filter((item) => item.value)
      .filter((item, index, all) => all.findIndex((candidate) => `${candidate.type}:${candidate.value}` === `${item.type}:${item.value}`) === index)
      .slice(0, 8);
  } catch {
    suggestions.value = [];
  } finally {
    isSuggesting.value = false;
  }
}

async function search(append: boolean) {
  const text = query.value.trim();
  if (!text && selectedTags.value.length === 0) {
    results.value = [];
    facets.value = [];
    partialErrors.value = [];
    hasSearched.value = false;
    syncRoute();
    return;
  }
  isLoading.value = true;
  hasSearched.value = true;
  try {
    const cursor = append ? nextCursor.value : activeCursor.value;
    const response = await ProfileService.search({
      q: text,
      types: selectedTypes.value,
      tags: selectedTags.value,
      providers: selectedProviders.value,
      author: selectedAuthor.value || undefined,
      dateFrom: dateFrom(selectedDateRange.value),
      sort: sortMode.value,
      limit: 30,
      cursor,
    });
    results.value = append ? [...results.value, ...response.items] : response.items;
    facets.value = response.facets || [];
    providerStatuses.value = response.providerStatuses || [];
    partialErrors.value = response.partialErrors || [];
    nextCursor.value = response.nextCursor || null;
    activeCursor.value = cursor;
    saveHistory({
      query: text,
      types: selectedTypes.value,
      providers: selectedProviders.value,
      tags: selectedTags.value,
      author: selectedAuthor.value || undefined,
      dateRange: selectedDateRange.value || undefined,
      sort: sortMode.value,
      searchedAt: new Date().toISOString(),
    });
    saveFilters();
    syncRoute();
  } catch (error) {
    toast.add({ severity: "error", summary: "Search", detail: error instanceof Error ? error.message : "Unable to search", life: 5000 });
  } finally {
    isLoading.value = false;
  }
}

function toggleFacet(facet: SearchFacet) {
  if (facet.group === "type") toggleListValue(selectedTypes, facet.value as SearchTypeFilter);
  if (facet.group === "provider") toggleListValue(selectedProviders, facet.value);
  if (facet.group === "tag") toggleListValue(selectedTags, facet.value);
  if (facet.group === "owner") selectedAuthor.value = selectedAuthor.value === facet.value ? "" : facet.value;
  if (facet.group === "dateRange") selectedDateRange.value = selectedDateRange.value === facet.value ? "" : facet.value as DateRange;
  nextCursor.value = null;
  activeCursor.value = null;
  void search(false);
}

function setSort(sort: SortMode) {
  sortMode.value = sort;
  activeCursor.value = null;
  if (hasSearched.value || query.value.trim() || selectedTags.value.length > 0) void search(false);
}

function clearFilters() {
  selectedTypes.value = [];
  selectedProviders.value = [];
  selectedTags.value = [];
  selectedAuthor.value = "";
  selectedDateRange.value = "";
  activeCursor.value = null;
  if (hasSearched.value || query.value.trim()) void search(false);
}

function clearQuery() {
  query.value = "";
  suggestions.value = [];
  if (selectedTags.value.length > 0) void search(false);
  else {
    results.value = [];
    hasSearched.value = false;
    syncRoute();
  }
}

function submitSearch() {
  nextCursor.value = null;
  activeCursor.value = null;
  void search(false);
}

function loadMore() {
  if (!nextCursor.value || isLoading.value) return;
  void search(true);
}

function applyHistory(item: SearchHistoryItem) {
  query.value = item.query;
  selectedTypes.value = item.types || [];
  selectedProviders.value = item.providers || [];
  selectedTags.value = item.tags || [];
  selectedAuthor.value = item.author || "";
  selectedDateRange.value = item.dateRange || "";
  sortMode.value = item.sort || "relevance";
  filtersOpen.value = false;
  void search(false);
}

function applySuggestion(suggestion: SearchSuggestion) {
  if (suggestion.owner) {
    const prefix = suggestion.owner.ownerType === "ORGANIZATION" ? "o" : "u";
    void router.push(withEmbedQuery(route, `/${prefix}/${encodeURIComponent(suggestion.owner.username)}`));
    return;
  }
  if (suggestion.type === "TAG") {
    const tag = suggestion.value.replace(/^#/, "");
    if (!selectedTags.value.includes(tag)) selectedTags.value = [...selectedTags.value, tag];
    query.value = "";
  } else {
    query.value = suggestion.value;
  }
  suggestions.value = [];
  void search(false);
}

function openResult(item: SearchItem) {
  if (isContentPath(item.url)) {
    if (postEmbedNavigation(route, { serviceKey: "content", path: item.url, url: contentUrl(item.url, true) })) return;
    window.location.assign(contentUrl(item.url, true));
    return;
  }
  void router.push(withEmbedQuery(route, item.url));
}

function openDiscover(item: FeedItem) {
  if (postEmbedNavigation(route, { serviceKey: "content", path: `/p/${encodeURIComponent(item.post.id)}`, url: contentUrl(`/p/${encodeURIComponent(item.post.id)}`, true) })) return;
  window.location.assign(contentUrl(`/p/${encodeURIComponent(item.post.id)}`, true));
}

function iconFor(item: SearchItem): string {
  if (item.type === "COLLECTION") return "pi pi-folder";
  if (item.type === "TAG") return "pi pi-hashtag";
  return "pi pi-file";
}

function thumbnailSrc(item: SearchItem): string {
  const source = item.thumbnailUrl || "";
  if (!source) return "";
  if (/^https?:\/\//.test(source)) return source;
  return source.startsWith("/") ? contentUrl(source) : source;
}

function ownerLabel(item: SearchItem): string {
  return item.owner?.displayName || item.owner?.username || item.meta.ownerId || "";
}

function resultSubtitle(item: SearchItem): string {
  return [item.typeLabel || item.type, item.providerLabel, ownerLabel(item)].filter(Boolean).join(" · ");
}

function resultDate(item: SearchItem): string {
  if (!item.createdAt) return "";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(new Date(item.createdAt));
}

function facetActive(facet: SearchFacet): boolean {
  if (facet.group === "type") return selectedTypes.value.includes(facet.value as SearchTypeFilter);
  if (facet.group === "provider") return selectedProviders.value.includes(facet.value);
  if (facet.group === "tag") return selectedTags.value.includes(facet.value);
  if (facet.group === "owner") return selectedAuthor.value === facet.value;
  if (facet.group === "dateRange") return selectedDateRange.value === facet.value;
  return facet.selected;
}

function applyRouteState() {
  query.value = stringQuery(route.query.q);
  selectedTypes.value = csvQuery(route.query.types).filter((value): value is SearchTypeFilter => ["posts", "collections", "tags"].includes(value));
  selectedProviders.value = csvQuery(route.query.providers);
  selectedTags.value = csvQuery(route.query.tags).map((tag) => tag.replace(/^#/, ""));
  selectedAuthor.value = stringQuery(route.query.author);
  selectedDateRange.value = ["day", "week", "month"].includes(stringQuery(route.query.dateRange)) ? stringQuery(route.query.dateRange) as DateRange : "";
  sortMode.value = ["relevance", "new", "popular"].includes(stringQuery(route.query.sort)) ? stringQuery(route.query.sort) as SortMode : "relevance";
  activeCursor.value = stringQuery(route.query.cursor) || null;
}

function syncRoute() {
  void router.replace({
    path: "/search",
    query: {
      ...embedQuery(route),
      q: query.value.trim() || undefined,
      types: selectedTypes.value.length ? selectedTypes.value.join(",") : undefined,
      providers: selectedProviders.value.length ? selectedProviders.value.join(",") : undefined,
      tags: selectedTags.value.length ? selectedTags.value.join(",") : undefined,
      author: selectedAuthor.value || undefined,
      dateRange: selectedDateRange.value || undefined,
      sort: sortMode.value !== "relevance" ? sortMode.value : undefined,
      cursor: activeCursor.value || undefined,
    },
  });
}

function restoreFilters() {
  try {
    const parsed = JSON.parse(localStorage.getItem(SEARCH_FILTERS_KEY) || "null") as Partial<SearchHistoryItem> | null;
    if (!parsed) return;
    selectedTypes.value = (parsed.types || []) as SearchTypeFilter[];
    selectedProviders.value = parsed.providers || [];
    selectedTags.value = parsed.tags || [];
    selectedAuthor.value = parsed.author || "";
    selectedDateRange.value = parsed.dateRange || "";
    sortMode.value = parsed.sort || "relevance";
  } catch {
    localStorage.removeItem(SEARCH_FILTERS_KEY);
  }
}

function saveFilters() {
  localStorage.setItem(SEARCH_FILTERS_KEY, JSON.stringify({
    types: selectedTypes.value,
    providers: selectedProviders.value,
    tags: selectedTags.value,
    author: selectedAuthor.value || undefined,
    dateRange: selectedDateRange.value || undefined,
    sort: sortMode.value,
  }));
}

function loadHistory(): SearchHistoryItem[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(SEARCH_HISTORY_KEY) || "[]");
    return Array.isArray(parsed) ? parsed.slice(0, 10) : [];
  } catch {
    return [];
  }
}

function saveHistory(item: SearchHistoryItem) {
  if (!item.query && item.tags.length === 0) return;
  history.value = [item, ...history.value.filter((existing) => existing.query.toLowerCase() !== item.query.toLowerCase())].slice(0, 10);
  localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(history.value));
}

function toggleListValue<T extends string>(target: { value: T[] }, value: T) {
  target.value = target.value.includes(value)
    ? target.value.filter((item) => item !== value)
    : [...target.value, value];
}

function dateFrom(range: DateRange): string | undefined {
  if (!range) return undefined;
  const days = range === "day" ? 1 : range === "week" ? 7 : 30;
  return new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
}

function csvQuery(value: unknown): string[] {
  const raw = Array.isArray(value) ? value.join(",") : typeof value === "string" ? value : "";
  return raw.split(",").map((item) => item.trim()).filter(Boolean);
}

function stringQuery(value: unknown): string {
  return Array.isArray(value) ? String(value[0] || "") : typeof value === "string" ? value : "";
}
</script>

<template>
  <main class="search-shell">
    <section class="search-command">
      <div class="search-bar" :class="{ 'is-focused': isSearchFocused }">
        <i class="pi pi-search"></i>
        <input
          v-model="query"
          type="search"
          placeholder="Search posts, collections and tags"
          @focus="isSearchFocused = true"
          @keydown.enter.prevent="submitSearch"
        />
        <button v-if="query" type="button" class="icon-button" aria-label="Clear search" @click="clearQuery">
          <i class="pi pi-times"></i>
        </button>
        <button type="button" class="submit-button" :disabled="isLoading || (!query.trim() && selectedTags.length === 0)" aria-label="Search" @click="submitSearch">
          <i :class="isLoading ? 'pi pi-spin pi-spinner' : 'pi pi-arrow-right'"></i>
        </button>
      </div>

      <section v-if="showHistory" class="suggest-panel" aria-label="Recent searches">
        <button v-for="item in visibleHistory" :key="item.searchedAt" type="button" @click="applyHistory(item)">
          <i class="pi pi-history"></i>
          <span>{{ item.query || item.tags.map((tag) => `#${tag}`).join(" ") }}</span>
        </button>
      </section>

      <section v-else-if="showSuggestions" class="suggest-panel" aria-label="Search suggestions">
        <button v-for="suggestion in suggestions" :key="`${suggestion.type}:${suggestion.value}`" type="button" @click="applySuggestion(suggestion)">
          <i :class="suggestion.owner ? 'pi pi-user' : suggestion.type === 'TAG' ? 'pi pi-hashtag' : 'pi pi-search'"></i>
          <span>{{ suggestion.label }}</span>
        </button>
      </section>
    </section>

    <div class="search-layout">
      <aside class="filter-sidebar" aria-label="Search filters">
        <div class="filter-heading">
          <span>Filters</span>
          <button v-if="activeFilterCount" type="button" @click="clearFilters">Clear</button>
        </div>
        <section v-for="group in facetGroups" :key="group.key" class="facet-group">
          <h2><i :class="group.icon"></i>{{ group.title }}</h2>
          <button
            v-for="facet in group.items"
            :key="`${facet.group}:${facet.value}`"
            type="button"
            :class="{ active: facetActive(facet) }"
            @click="toggleFacet(facet)"
          >
            <span>{{ facet.label }}</span>
            <strong>{{ facet.count }}</strong>
          </button>
        </section>
      </aside>

      <section class="results-column">
        <div class="mobile-filter-row">
          <button type="button" @click="filtersOpen = true">
            <i class="pi pi-sliders-h"></i>
            <span>Filters</span>
            <strong v-if="activeFilterCount">{{ activeFilterCount }}</strong>
          </button>
        </div>

        <section v-if="degradedStatuses.length || partialErrors.length" class="provider-strip" aria-label="Search provider status">
          <span v-for="status in degradedStatuses" :key="status.providerKey">
            <i class="pi pi-exclamation-circle"></i>
            {{ status.label }} {{ status.status }}
          </span>
          <span v-for="error in partialErrors.slice(0, 2)" :key="error">
            <i class="pi pi-info-circle"></i>
            {{ error }}
          </span>
        </section>

        <div class="result-toolbar">
          <div>
            <strong>{{ results.length ? `${results.length} results` : hasSearched ? "No results" : "Search" }}</strong>
            <span v-if="activeFilterCount">{{ activeFilterCount }} filters active</span>
          </div>
          <div class="sort-control" aria-label="Sort results">
            <button
              v-for="sort in sortFilters"
              :key="sort.id"
              type="button"
              :class="{ active: sortMode === sort.id }"
              @click="setSort(sort.id)"
            >
              <i :class="sort.icon"></i>
              <span>{{ sort.label }}</span>
            </button>
          </div>
        </div>

        <section v-if="showDiscover" class="start-state" aria-label="Search start">
          <div v-if="history.length" class="recent-list">
            <h2>Recent searches</h2>
            <button v-for="item in history.slice(0, 5)" :key="item.searchedAt" type="button" @click="applyHistory(item)">
              <i class="pi pi-history"></i>
              <span>{{ item.query || item.tags.map((tag) => `#${tag}`).join(" ") }}</span>
            </button>
          </div>
          <div v-if="discoverItems.length" class="discover-grid">
            <article v-for="item in discoverItems" :key="item.post.id" class="discover-item" @click="openDiscover(item)">
              <strong>{{ postSnippet(item.post) }}</strong>
              <p>{{ item.post.author?.displayName || displayUsername(item.post.author?.username || item.post.authorName || "user") }}</p>
              <footer>
                <span v-for="tag in item.post.tags.slice(0, 3)" :key="tag">#{{ tag }}</span>
              </footer>
            </article>
          </div>
        </section>

        <section v-if="results.length > 0" class="result-list" aria-label="Search results">
          <article v-for="item in results" :key="`${item.type}:${item.id}`" class="result-row" @click="openResult(item)">
            <span class="result-thumb">
              <img v-if="thumbnailSrc(item)" :src="thumbnailSrc(item)" alt="" />
              <i v-else :class="iconFor(item)"></i>
            </span>
            <span class="result-copy">
              <span class="result-meta">
                <small>{{ resultSubtitle(item) }}</small>
                <time v-if="resultDate(item)">{{ resultDate(item) }}</time>
              </span>
              <strong>{{ item.title || item.snippet || item.id }}</strong>
              <p v-if="(item.highlights && item.highlights[0]) || item.snippet">{{ (item.highlights && item.highlights[0]) || item.snippet }}</p>
              <footer>
                <span v-for="tag in item.tags.slice(0, 4)" :key="tag">#{{ tag }}</span>
                <span v-if="item.type === 'COLLECTION'">{{ item.meta.itemCount || 0 }} items</span>
                <span v-if="item.meta.likeCount">{{ item.meta.likeCount }} likes</span>
              </footer>
            </span>
          </article>
        </section>

        <section v-else-if="hasSearched && !isLoading" class="empty-state">
          <i class="pi pi-search"></i>
          <strong>No results</strong>
          <span>Try a broader query or clear one of the filters.</span>
        </section>

        <button v-if="nextCursor" type="button" class="load-more" :disabled="isLoading" @click="loadMore">
          <i :class="isLoading ? 'pi pi-spin pi-spinner' : 'pi pi-plus'"></i>
          <span>Load more</span>
        </button>
      </section>
    </div>

    <div v-if="filtersOpen" class="filter-drawer" role="dialog" aria-modal="true" aria-label="Search filters">
      <button type="button" class="drawer-backdrop" aria-label="Close filters" @click="filtersOpen = false"></button>
      <section class="drawer-panel">
        <div class="filter-heading">
          <span>Filters</span>
          <button type="button" @click="filtersOpen = false">Done</button>
        </div>
        <section v-for="group in facetGroups" :key="group.key" class="facet-group">
          <h2><i :class="group.icon"></i>{{ group.title }}</h2>
          <button
            v-for="facet in group.items"
            :key="`${facet.group}:${facet.value}`"
            type="button"
            :class="{ active: facetActive(facet) }"
            @click="toggleFacet(facet)"
          >
            <span>{{ facet.label }}</span>
            <strong>{{ facet.count }}</strong>
          </button>
        </section>
      </section>
    </div>
  </main>
</template>

<style scoped>
.search-shell {
  min-height: 100dvh;
  background: #fafafa;
  color: #111827;
  padding: 78px clamp(12px, 3vw, 28px) 42px;
}

.search-command {
  position: sticky;
  z-index: 30;
  top: 12px;
  width: min(720px, 100%);
  margin: 0 auto 20px;
}

.search-bar {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto 40px;
  align-items: center;
  gap: 8px;
  min-height: 48px;
  padding: 5px 6px 5px 16px;
  border: 1px solid #dbdbdb;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.07);
  backdrop-filter: blur(14px);
}

.search-bar.is-focused {
  border-color: #a8a8a8;
}

.search-bar input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
}

button {
  border: 0;
  font: inherit;
}

.icon-button,
.submit-button {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  cursor: pointer;
}

.icon-button {
  background: transparent;
  color: var(--muted);
}

.submit-button {
  background: #0095f6;
  color: #ffffff;
}

.submit-button:disabled {
  opacity: 0.45;
  cursor: default;
}

.suggest-panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  display: grid;
  gap: 4px;
  padding: 8px;
  border: 1px solid #dbdbdb;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.14);
}

.suggest-panel button,
.recent-list button,
.facet-group button,
.mobile-filter-row button,
.sort-control button,
.load-more {
  cursor: pointer;
}

.suggest-panel button,
.recent-list button {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 0 10px;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  text-align: left;
}

.search-layout {
  width: min(975px, 100%);
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.filter-sidebar {
  position: static;
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 2px 0 8px;
  scrollbar-width: none;
}

.filter-sidebar::-webkit-scrollbar {
  display: none;
}

.filter-heading,
.result-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.filter-heading span,
.result-toolbar strong {
  font-weight: 900;
}

.filter-heading button {
  min-height: 30px;
  padding: 0 10px;
  border-radius: 999px;
  background: #efefef;
  color: #111827;
  cursor: pointer;
  font-weight: 800;
}

.facet-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}

.facet-group h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: #737373;
  font-size: 11px;
  font-weight: 900;
  text-transform: uppercase;
}

.facet-group button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  background: #ffffff;
  border: 1px solid #dbdbdb;
  color: #111827;
  text-align: left;
}

.facet-group button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.facet-group button strong {
  color: #737373;
  font-size: 12px;
}

.facet-group button:hover,
.facet-group button.active,
.suggest-panel button:hover,
.recent-list button:hover,
.result-row:hover,
.discover-item:hover {
  background: #efefef;
}

.facet-group button.active {
  border-color: #111827;
  background: #111827;
  color: #ffffff;
  font-weight: 900;
}

.facet-group button.active strong {
  color: rgba(255, 255, 255, 0.74);
}

.results-column {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.mobile-filter-row {
  display: none;
}

.provider-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.provider-strip span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  padding: 0 10px;
  border-radius: 999px;
  background: #fef3c7;
  color: #7c2d12;
  font-size: 12px;
  font-weight: 800;
}

.result-toolbar {
  min-height: 40px;
  border-bottom: 1px solid #dbdbdb;
  padding-bottom: 10px;
}

.result-toolbar div:first-child {
  display: grid;
  gap: 2px;
}

.result-toolbar span {
  color: #737373;
  font-size: 12px;
}

.sort-control {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: 999px;
  background: #efefef;
}

.sort-control button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 10px;
  border-radius: 999px;
  background: transparent;
  color: #737373;
  font-weight: 800;
}

.sort-control button.active {
  background: #ffffff;
  color: #111827;
}

.start-state {
  display: grid;
  gap: 12px;
}

.recent-list {
  display: grid;
  gap: 4px;
}

.recent-list h2 {
  margin: 0;
  font-size: 13px;
}

.discover-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
}

.discover-item,
.result-row {
  border: 1px solid #dbdbdb;
  border-radius: 0;
  background: #ffffff;
  cursor: pointer;
}

.discover-item {
  aspect-ratio: 1;
  padding: 12px;
  display: grid;
  align-content: end;
  gap: 10px;
  background: linear-gradient(180deg, #f5f5f5, #ffffff);
}

.discover-item p,
.result-copy p,
.result-meta,
.empty-state span {
  color: #737373;
}

.discover-item footer,
.result-copy footer {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  font-weight: 800;
  color: var(--muted);
}

.result-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
}

.result-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
  padding: 0;
  overflow: hidden;
  position: relative;
}

.result-thumb {
  width: 100%;
  aspect-ratio: 1;
  height: auto;
  border-radius: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: #efefef;
  color: #111827;
  font-size: 28px;
}

.result-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.result-copy {
  min-width: 0;
  display: grid;
  gap: 5px;
  align-content: start;
  padding: 10px;
}

.result-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  font-weight: 800;
}

.result-copy strong,
.result-copy p,
.result-meta small {
  overflow-wrap: anywhere;
}

.result-copy p {
  margin: 0;
}

.empty-state {
  min-height: 220px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  border: 1px dashed #dbdbdb;
  border-radius: 12px;
  color: #737373;
  background: #ffffff;
}

.empty-state strong {
  color: #111827;
}

.load-more {
  justify-self: center;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 14px;
  border-radius: 999px;
  background: #0095f6;
  color: #ffffff;
  font-weight: 900;
}

.filter-drawer {
  position: fixed;
  z-index: 100;
  inset: 0;
}

.drawer-backdrop {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  background: rgba(15, 23, 42, 0.32);
}

.drawer-panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: 82dvh;
  overflow: auto;
  display: grid;
  gap: 14px;
  padding: 16px;
  border-radius: 14px 14px 0 0;
  background: #ffffff;
}

@media (max-width: 860px) {
  .search-shell {
    padding: 76px 10px 34px;
  }

  .search-layout {
    grid-template-columns: 1fr;
  }

  .filter-sidebar {
    display: none;
  }

  .mobile-filter-row {
    display: flex;
  }

  .mobile-filter-row button {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-height: 38px;
    padding: 0 12px;
    border-radius: 999px;
    background: #ffffff;
    border: 1px solid #dbdbdb;
    color: #111827;
    font-weight: 900;
  }

  .mobile-filter-row strong {
    min-width: 20px;
    height: 20px;
    border-radius: 999px;
    display: grid;
    place-items: center;
    background: #0095f6;
    color: #ffffff;
    font-size: 12px;
  }

  .result-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .sort-control {
    width: 100%;
    overflow-x: auto;
  }

  .discover-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 540px) {
  .search-bar {
    grid-template-columns: auto minmax(0, 1fr) auto 40px;
    min-height: 52px;
    padding-left: 12px;
  }

  .icon-button,
  .submit-button {
    width: 38px;
    height: 38px;
  }

  .result-meta {
    display: grid;
    gap: 2px;
  }

  .result-list,
  .discover-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
