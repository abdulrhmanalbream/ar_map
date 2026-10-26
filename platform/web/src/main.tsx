import React from 'react';
import ReactDOM from 'react-dom/client';
import './base.css';

// Each public surface is isolated from administrator sessions and private polling.
const Landing = React.lazy(() => import('./landing/LandingPage'));
const Dashboard = React.lazy(() => import('./App'));
const GuestDashboard = React.lazy(() => import('./GuestDashboard'));
const route = window.location.pathname.replace(/\/+$/, '') || '/';
const isDashboard = route === '/dashboard';
const isAdmin = route === '/dashboard/admin';
document.title = isAdmin ? 'المطوف الذكي | لوحة المشرف' : isDashboard ? 'المطوف الذكي | الداشبورد' : 'المطوف الذكي | مع كل نظرة، معنى أقرب';

function Route() {
  if (route !== '/' && !isDashboard && !isAdmin) return (
    <main className="route-notice" dir="rtl">
      <h1>هذه الصفحة غير موجودة</h1>
      <p>يمكنك العودة للتعرف على مشروع المطوف الذكي.</p>
      <a href="/">العودة إلى المشروع</a>
    </main>
  );
  return <React.Suspense fallback={<main className="route-notice" dir="rtl" role="status">جارٍ فتح المطوف الذكي…</main>}>
    {isAdmin ? <Dashboard /> : isDashboard ? <GuestDashboard /> : <Landing />}
  </React.Suspense>;
}

ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><Route /></React.StrictMode>);
