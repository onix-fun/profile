import { createI18n } from "vue-i18n";
import { getPreferences, preferenceController } from "@/shared/lib/preferences";

const messages = {
  en: {
    common: { loading: "Loading", retry: "Try again later." },
    nav: { feed: "Feed", profile: "Profile", social: "Connections", search: "Search", create: "Create", createPost: "New post", createStory: "New story", drafts: "Drafts", signIn: "Sign in", settings: "Settings", menu: "Open navigation", close: "Close navigation" },
    profile: { loading: "Loading profile", notFound: "Profile not found", notFoundHint: "The username does not exist or is no longer available.", unavailable: "Profile unavailable", failed: "Unable to load profile" },
  },
  ru: {
    common: { loading: "Загрузка", retry: "Попробуйте ещё раз позже." },
    nav: { feed: "Лента", profile: "Профиль", social: "Связи", search: "Поиск", create: "Создать", createPost: "Новый пост", createStory: "Новая история", drafts: "Черновики", signIn: "Войти", settings: "Настройки", menu: "Открыть навигацию", close: "Закрыть навигацию" },
    profile: { loading: "Загрузка профиля", notFound: "Профиль не найден", notFoundHint: "Такого имени пользователя нет или оно больше недоступно.", unavailable: "Профиль недоступен", failed: "Не удалось загрузить профиль" },
  },
} as const;

export const i18n = createI18n({ legacy: false, locale: getPreferences().locale, fallbackLocale: "en", messages });
preferenceController.subscribe((preferences) => { i18n.global.locale.value = preferences.locale; });
