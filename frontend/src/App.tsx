import { lazy, useEffect } from 'react';
import { App as AntApp, ConfigProvider, Spin } from 'antd';
import arEG from 'antd/locale/ar_EG';
import enUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes } from 'react-router';
import { useAuth } from './auth/AuthContext';
import { AppShell } from './components/AppShell';
import { applyDocumentLanguage, directionOf, isLanguage } from './i18n';
import { themeFor } from './theme';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';

// Screens load on first use, so sign-in and home do not wait for PDF.js and the admin screens
const admin = () => import('./pages/admin/AdminPage');
const AdminHome = lazy(() => admin().then((module) => ({ default: module.AdminHome })));
const DocumentTypesPage = lazy(() => admin().then((module) => ({ default: module.DocumentTypesPage })));
const TypeEditorPage = lazy(() => admin().then((module) => ({ default: module.TypeEditorPage })));
const LanguagesPage = lazy(() => admin().then((module) => ({ default: module.LanguagesPage })));
const PendingChangesPage = lazy(() => admin().then((module) => ({ default: module.PendingChangesPage })));
const AccessRulesPage = lazy(() => admin().then((module) => ({ default: module.AccessRulesPage })));
const FoldersPage = lazy(() => admin().then((module) => ({ default: module.FoldersPage })));
const CommitteesPage = lazy(() => admin().then((module) => ({ default: module.CommitteesPage })));
const UsersPage = lazy(() => admin().then((module) => ({ default: module.UsersPage })));
const AuditPage = lazy(() => import('./pages/AuditPage').then((module) => ({ default: module.AuditPage })));
const BrowsePage = lazy(() => import('./pages/BrowsePage').then((module) => ({ default: module.BrowsePage })));
const DocumentPage = lazy(() => import('./pages/DocumentPage').then((module) => ({ default: module.DocumentPage })));
const RecordsPage = lazy(() => import('./pages/RecordsPage').then((module) => ({ default: module.RecordsPage })));
const RecycleBinPage = lazy(() => import('./pages/RecycleBinPage').then((module) => ({ default: module.RecycleBinPage })));
const SearchPage = lazy(() => import('./pages/SearchPage').then((module) => ({ default: module.SearchPage })));
const TasksPage = lazy(() => import('./pages/TasksPage').then((module) => ({ default: module.TasksPage })));
const UploadPage = lazy(() => import('./pages/UploadPage').then((module) => ({ default: module.UploadPage })));

export function App() {
  const { i18n } = useTranslation();
  const { user, loading } = useAuth();
  const language = isLanguage(i18n.resolvedLanguage) ? i18n.resolvedLanguage : 'en';

  useEffect(() => {
    applyDocumentLanguage(language);
  }, [language]);

  return (
    <ConfigProvider direction={directionOf(language)} locale={language === 'ar' ? arEG : enUS} theme={themeFor(language)}>
      <AntApp>
        {loading ? (
          <div className="centered"><Spin size="large" /></div>
        ) : !user ? (
          <LoginPage />
        ) : (
          <Routes>
            <Route element={<AppShell />}>
              <Route index element={<HomePage />} />
              <Route path="browse/:cabinet?/:folderId?" element={<BrowsePage />} />
              <Route path="upload" element={<UploadPage />} />
              <Route path="documents/:id" element={<DocumentPage />} />
              <Route path="tasks" element={<TasksPage />} />
              <Route path="tasks/:taskId" element={<TasksPage />} />
              <Route path="search" element={<SearchPage />} />
              <Route path="recycle-bin" element={<RecycleBinPage />} />
              <Route path="records" element={<RecordsPage />} />
              <Route path="admin" element={<AdminHome />} />
              <Route path="admin/types" element={<DocumentTypesPage />} />
              <Route path="admin/types/new" element={<TypeEditorPage />} />
              <Route path="admin/types/:code" element={<DocumentTypesPage />} />
              <Route path="admin/types/:code/propose" element={<TypeEditorPage />} />
              <Route path="admin/languages" element={<LanguagesPage />} />
              <Route path="admin/changes" element={<PendingChangesPage />} />
              <Route path="admin/access" element={<AccessRulesPage />} />
              <Route path="admin/folders" element={<FoldersPage />} />
              <Route path="admin/committees" element={<CommitteesPage />} />
              <Route path="admin/users" element={<UsersPage />} />
              <Route path="audit" element={<AuditPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Route>
          </Routes>
        )}
      </AntApp>
    </ConfigProvider>
  );
}
