<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { ProfileService } from "@/api/profileService";
import type { SessionUser } from "@/api/types";
import { runtimeConfig } from "@/runtime-config";
import { accountSettingsUrl } from "@/features/profile/accountLinks";

const router = useRouter();
const route = useRoute();
const user = ref<SessionUser | null>(null);
const menuOpen = ref(false);

const initials = computed(() => {
  const source = user.value?.firstName || user.value?.username || "U";
  return source.slice(0, 1).toUpperCase();
});
const chromeHidden = computed(() => route.name === "CreatePost" || route.name === "CreateStory");
const headerVisible = computed(() => !chromeHidden.value && route.path !== "/");
const settingsHref = computed(() => accountSettingsUrl(runtimeConfig.accountFrontendUrl, window.location.href));

onMounted(async () => {
  user.value = await ProfileService.session();
});

function closeMenu() {
  menuOpen.value = false;
}

function openCreatePost() {
  closeMenu();
  void router.push("/post/new");
}

function openCreateStory() {
  closeMenu();
  void router.push("/story/new");
}
</script>

<template>
  <div class="app-shell" @keydown.esc="closeMenu">
    <header v-if="headerVisible" class="app-header" aria-label="Main navigation">
      <RouterLink class="brand-mark" to="/" aria-label="Open feed">
        <span class="brand-word">Onix</span>
      </RouterLink>
    </header>

    <button
      v-if="!chromeHidden"
      class="avatar-menu-button"
      type="button"
      aria-label="Open menu"
      :aria-expanded="menuOpen"
      @click="menuOpen = !menuOpen"
    >
      <img v-if="user?.avatarUrl" :src="user.avatarUrl" alt="" />
      <span v-else>{{ initials }}</span>
      <i class="pi pi-bars"></i>
    </button>

    <Transition name="menu-fade">
      <aside v-if="menuOpen && !chromeHidden" class="account-menu" aria-label="Account menu">
        <div class="account-menu__identity">
          <div class="account-menu__avatar">
            <img v-if="user?.avatarUrl" :src="user.avatarUrl" alt="" />
            <span v-else>{{ initials }}</span>
          </div>
          <div>
            <strong>{{ user?.firstName || user?.username || "Account" }}</strong>
            <span>@{{ user?.username }}</span>
          </div>
        </div>

        <nav class="account-menu__nav">
          <RouterLink to="/" @click="closeMenu"><i class="pi pi-home"></i>Feed</RouterLink>
          <RouterLink to="/u/me" @click="closeMenu"><i class="pi pi-user"></i>Profile</RouterLink>
          <RouterLink to="/search" @click="closeMenu"><i class="pi pi-search"></i>Search</RouterLink>
          <button type="button" @click="openCreatePost"><i class="pi pi-plus-circle"></i>Create post</button>
          <button type="button" @click="openCreateStory"><i class="pi pi-stopwatch"></i>Create story</button>
          <a :href="settingsHref" @click="closeMenu"><i class="pi pi-cog"></i>Settings</a>
        </nav>
      </aside>
    </Transition>

    <main class="app-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100dvh;
  background:
    radial-gradient(circle at 18% 22%, rgba(59, 130, 246, 0.08), transparent 28%),
    linear-gradient(180deg, #ffffff 0%, #f7f9fb 100%);
}

.app-header {
  position: fixed;
  z-index: 80;
  top: 0;
  left: 0;
  right: 0;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 18px clamp(18px, 3vw, 42px);
  pointer-events: none;
}

.brand-mark,
.avatar-menu-button {
  pointer-events: auto;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #111827;
  text-decoration: none;
  font-weight: 900;
  letter-spacing: 0;
}

.brand-word {
  font-size: 20px;
}

.avatar-menu-button {
  position: fixed;
  z-index: 86;
  right: clamp(18px, 3vw, 42px);
  bottom: max(22px, env(safe-area-inset-bottom));
  width: 48px;
  height: 48px;
  border: 1px solid rgba(17, 24, 39, 0.1);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.76);
  color: #111827;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.13);
  cursor: pointer;
  overflow: visible;
}

.avatar-menu-button img,
.avatar-menu-button > span {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  border-radius: inherit;
  object-fit: cover;
  font-weight: 900;
}

.avatar-menu-button i {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  font-size: 10px;
}

.account-menu {
  position: fixed;
  z-index: 90;
  right: clamp(14px, 3vw, 34px);
  bottom: calc(max(22px, env(safe-area-inset-bottom)) + 64px);
  width: min(320px, calc(100vw - 28px));
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 26px 70px rgba(15, 23, 42, 0.18);
  backdrop-filter: blur(18px);
}

.account-menu__identity {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 8px 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
}

.account-menu__avatar {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  font-weight: 900;
  overflow: hidden;
}

.account-menu__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.account-menu__identity strong,
.account-menu__identity span {
  display: block;
}

.account-menu__identity span {
  color: #667085;
  font-size: 13px;
  font-weight: 700;
}

.account-menu__nav {
  display: grid;
  gap: 4px;
  padding-top: 10px;
}

.account-menu__nav a,
.account-menu__nav button {
  width: 100%;
  height: 42px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 8px;
  padding: 0 10px;
  background: transparent;
  color: #111827;
  text-decoration: none;
  font: inherit;
  font-weight: 800;
  cursor: pointer;
}

.account-menu__nav a:hover,
.account-menu__nav button:hover {
  background: #f1f5f9;
}

.app-main {
  min-height: 100dvh;
}

.menu-fade-enter-active,
.menu-fade-leave-active {
  transition: opacity 140ms ease, transform 140ms ease;
}

.menu-fade-enter-from,
.menu-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
