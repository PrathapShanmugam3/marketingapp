import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { en } from '../locales/en';
import { ta } from '../locales/ta';

const saved = (() => { try { return localStorage.getItem('mp_lang') || 'en'; } catch { return 'en'; } })();

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, ta: { translation: ta } },
  lng: saved,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
  returnEmptyString: false,
});

export const setLanguage = (lng: 'en' | 'ta') => {
  try { localStorage.setItem('mp_lang', lng); } catch { /* ignore */ }
  document.documentElement.lang = lng;
  i18n.changeLanguage(lng);
};

export default i18n;
