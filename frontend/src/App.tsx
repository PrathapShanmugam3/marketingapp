import { type ReactNode } from 'react';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './lib/auth';
import { ApiError } from './lib/api';
import { Shell, PublicLayout, type NavSection } from './layouts/Shell';
import { Loading, Toaster } from './components/ui';
import { ForgotReset, InviteAccept, Login, Signup, Verify } from './pages/auth';
import { Dashboard } from './pages/dashboard';
import { ContactForm, ContactsList, ImportContacts, TagsSegments } from './pages/contacts';
import { LeadDetail, LeadsBoard } from './pages/leads';
import { TemplateBuilder, TemplatesList, WhatsAppConnect } from './pages/whatsapp';
import { CampaignList, CampaignReport, CampaignWizard } from './pages/campaigns';
import { Inbox } from './pages/inbox';
import { Reviews } from './pages/reviews';
import { FormEditor, FormsList } from './pages/forms';
import { Social } from './pages/social';
import { Account, AgencyClients, ApiKeys, Billing, BusinessProfile, Domains, Team } from './pages/settings';
import { Apps, AutomationEditor, AutomationsList, LinkDetail, Links, PageEditor, PagesList } from './pages/growth';
import { Conversions, OfferEditor, OffersList } from './pages/offers';
import { EarnDashboard, EarnSignup, Earnings, Kyc, OfferWall, Payouts } from './pages/earn';
import { AdminHome, AdminRatesPlans, AdminSystem, AdminWorkspaces, Finance } from './pages/admin';
import { TicketThread, Tickets } from './pages/support';
import { BroadcastEditor, Broadcasts } from './pages/broadcasts';
import { Chatbot } from './pages/chatbot';
import { Catalog, Rewards } from './pages/commerce';
import { ContentCalendar, ContentEditor, SeoTools } from './pages/content';
import { AdDetail, AdsList } from './pages/ads';
import { Analytics } from './pages/analytics';
import { SocialDm } from './pages/dm';

const qc = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 15_000, refetchOnWindowFocus: false, retry: (n, e) => !(e instanceof ApiError && e.status >= 400 && e.status < 500) && n < 2 },
  },
});

function Guard({ allow, children }: { allow: 'business' | 'promoter' | 'admin'; children?: ReactNode }) {
  const { status, workspace, isAdmin, isPromoter } = useAuth();
  const loc = useLocation();
  if (status === 'loading') return <Loading />;
  if (status === 'anon') return <Navigate to="/login" replace state={{ from: loc.pathname + loc.search }} />;
  if (allow === 'business' && !workspace) return <Navigate to={isAdmin ? '/admin' : isPromoter ? '/earn' : '/login'} replace />;
  if (allow === 'promoter' && !isPromoter) return <Navigate to="/earn/join" replace />;
  if (allow === 'admin' && !isAdmin) return <Navigate to="/" replace />;
  return <>{children ?? <Outlet />}</>;
}

/** Login / signup pages bounce signed-in users to their portal. */
function PublicOnly() {
  const { status, workspace, isAdmin, isPromoter } = useAuth();
  if (status === 'loading') return <Loading />;
  if (status === 'authed') return <Navigate to={workspace ? '/' : isAdmin ? '/admin' : isPromoter ? '/earn' : '/'} replace />;
  return <PublicLayout />;
}

function BusinessShell() {
  const { can, hasFeature, role, workspace } = useAuth();
  const staff = role === 'STAFF';
  const m = can.manage;
  const sections: NavSection[] = [
    { items: [{ to: '/', key: 'nav.dashboard', label: 'Dashboard', icon: 'home', end: true, show: !staff }] },
    { title: 'Customers', items: [
      { to: '/contacts', key: 'nav.contacts', label: 'Contacts', icon: 'users' }, { to: '/leads', key: 'nav.leads', label: 'Leads', icon: 'briefcase' },
      { to: '/inbox', key: 'nav.inbox', label: 'Inbox', icon: 'chat' }, { to: '/social/dm', key: 'nav.dm', label: 'Social messages', icon: 'chat', show: hasFeature('dm') },
      { to: '/forms', key: 'nav.forms', label: 'Lead forms', icon: 'file', show: m }] },
    { title: 'Marketing', items: [
      { to: '/campaigns', key: 'nav.campaigns', label: 'Campaigns', icon: 'megaphone', show: m }, { to: '/templates', key: 'nav.templates', label: 'Templates', icon: 'send', show: m },
      { to: '/broadcasts', key: 'nav.broadcasts', label: 'E-mail, SMS, push', icon: 'send', show: m },
      { to: '/automations', key: 'nav.automations', label: 'Automations', icon: 'zap', show: m }, { to: '/chatbot', key: 'nav.chatbot', label: 'Chatbot', icon: 'zap', show: m },
      { to: '/reviews', key: 'nav.reviews', label: 'Reviews', icon: 'star' },
      { to: '/social', key: 'nav.social', label: 'Social posts', icon: 'globe', show: m && hasFeature('social') }] },
    { title: 'Sell and retain', items: [
      { to: '/catalog', key: 'nav.catalog', label: 'Catalog', icon: 'grid', show: m }, { to: '/rewards', key: 'nav.rewards', label: 'Coupons and loyalty', icon: 'tag', show: m }] },
    { title: 'Content and ads', items: [
      { to: '/content', key: 'nav.content', label: 'Content and SEO', icon: 'file', show: m }, { to: '/ads', key: 'nav.ads', label: 'Ad campaigns', icon: 'megaphone', show: m },
      { to: '/analytics', key: 'nav.analytics', label: 'Analytics', icon: 'chart', show: m }] },
    { title: 'Growth', items: [
      { to: '/links', key: 'nav.links', label: 'Smart links', icon: 'link', show: m }, { to: '/pages', key: 'nav.pages', label: 'Landing pages', icon: 'grid', show: m },
      { to: '/apps', key: 'nav.apps', label: 'App promotion', icon: 'phone', show: m }, { to: '/growth/offers', key: 'nav.offers', label: 'Offers', icon: 'cash', show: m }] },
    { title: 'Settings', items: [
      { to: '/whatsapp', key: 'nav.whatsapp', label: 'WhatsApp', icon: 'phone', show: can.owner }, { to: '/settings/billing', key: 'nav.billing', label: 'Billing & wallet', icon: 'wallet', show: can.owner },
      { to: '/settings/team', key: 'nav.team', label: 'Team', icon: 'users', show: can.owner }, { to: '/settings/clients', key: 'nav.agency', label: 'Client workspaces', icon: 'briefcase', show: role === 'AGENCY_ADMIN' },
      { to: '/settings/domains', key: 'nav.domains', label: 'Domains', icon: 'globe', show: can.owner }, { to: '/settings/api', key: 'nav.api', label: 'API keys', icon: 'key', show: can.owner },
      { to: '/settings/business', key: 'nav.business', label: 'Business profile', icon: 'settings', show: !!workspace }, { to: '/support', key: 'nav.support', label: 'Support', icon: 'help' }] },
  ];
  const tabs = staff
    ? [{ to: '/inbox', key: 'nav.inbox', label: 'Inbox', icon: 'chat' }, { to: '/leads', key: 'nav.leads', label: 'Leads', icon: 'briefcase' }, { to: '/contacts', key: 'nav.contacts', label: 'Contacts', icon: 'users' }]
    : [{ to: '/', key: 'nav.dashboard', label: 'Home', icon: 'home', end: true }, { to: '/inbox', key: 'nav.inbox', label: 'Inbox', icon: 'chat' }, { to: '/leads', key: 'nav.leads', label: 'Leads', icon: 'briefcase' }, { to: '/campaigns', key: 'nav.campaigns', label: 'Campaigns', icon: 'megaphone' }];
  return <Shell portal="business" sections={sections} mobileTabs={tabs} />;
}

function PromoterShell() {
  const sections: NavSection[] = [{ items: [
    { to: '/earn', key: 'nav.earn', label: 'Earnings', icon: 'cash', end: true }, { to: '/earn/offers', key: 'nav.offerwall', label: 'Offer wall', icon: 'grid' },
    { to: '/earn/history', key: 'nav.earn', label: 'History', icon: 'chart' }, { to: '/earn/payouts', key: 'nav.withdraw', label: 'Withdraw', icon: 'wallet' },
    { to: '/earn/kyc', key: 'nav.kyc', label: 'KYC', icon: 'shield' }, { to: '/earn/support', key: 'nav.support', label: 'Support', icon: 'help' }] }];
  const tabs = [{ to: '/earn', key: 'nav.earn', label: 'Earnings', icon: 'cash', end: true }, { to: '/earn/offers', key: 'nav.offerwall', label: 'Offers', icon: 'grid' }, { to: '/earn/payouts', key: 'nav.withdraw', label: 'Withdraw', icon: 'wallet' }];
  return <Shell portal="promoter" sections={sections} mobileTabs={tabs} />;
}

function AdminShell() {
  const { user } = useAuth();
  const finance = ['SUPER_ADMIN', 'FINANCE'].includes(user?.adminRole ?? '');
  const sections: NavSection[] = [{ items: [
    { to: '/admin', key: 'nav.admin', label: 'Overview', icon: 'home', end: true }, { to: '/admin/workspaces', key: 'nav.business', label: 'Workspaces', icon: 'briefcase' },
    { to: '/admin/finance', key: 'nav.finance', label: 'Finance', icon: 'cash', show: finance }, { to: '/admin/rates', key: 'nav.billing', label: 'Rates and plans', icon: 'wallet' },
    { to: '/admin/tickets', key: 'nav.support', label: 'Support tickets', icon: 'help' }, { to: '/admin/system', key: 'nav.settings', label: 'System', icon: 'settings' }] }];
  return <Shell portal="admin" sections={sections} />;
}

const NotFound = () => <div className="p-10 text-center"><h1 className="text-2xl font-bold">Page not found</h1><a className="text-brand underline" href="/">Go home</a></div>;

export default function App() {
  return (
    <QueryClientProvider client={qc}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route element={<PublicOnly />}>
              <Route path="/login" element={<Login />} /><Route path="/signup" element={<Signup />} /><Route path="/verify" element={<Verify />} />
              <Route path="/forgot" element={<ForgotReset />} /><Route path="/invite" element={<InviteAccept />} /><Route path="/earn/join" element={<EarnSignup />} />
            </Route>
            <Route path="/earn/signup" element={<PublicLayout><EarnSignup /></PublicLayout>} />
            <Route path="/onboarding" element={<Guard allow="business"><PublicLayout wide><BusinessProfile onboarding /></PublicLayout></Guard>} />

            <Route element={<Guard allow="business" />}>
              <Route element={<BusinessShell />}>
                <Route index element={<Dashboard />} />
                <Route path="contacts" element={<ContactsList />} /><Route path="contacts/new" element={<ContactForm />} /><Route path="contacts/import" element={<ImportContacts />} />
                <Route path="contacts/tags" element={<TagsSegments />} /><Route path="contacts/:id" element={<ContactForm />} />
                <Route path="leads" element={<LeadsBoard />} /><Route path="leads/:id" element={<LeadDetail />} />
                <Route path="inbox" element={<Inbox />} />
                <Route path="forms" element={<FormsList />} /><Route path="forms/:id" element={<FormEditor />} />
                <Route path="whatsapp" element={<WhatsAppConnect />} /><Route path="templates" element={<TemplatesList />} /><Route path="templates/new" element={<TemplateBuilder />} /><Route path="templates/:id" element={<TemplateBuilder />} />
                <Route path="campaigns" element={<CampaignList />} /><Route path="campaigns/new" element={<CampaignWizard />} /><Route path="campaigns/:id/edit" element={<CampaignWizard />} /><Route path="campaigns/:id" element={<CampaignReport />} />
                <Route path="automations" element={<AutomationsList />} /><Route path="automations/:id" element={<AutomationEditor />} />
                <Route path="reviews" element={<Reviews />} /><Route path="social" element={<Social />} /><Route path="social/dm" element={<SocialDm />} />
                <Route path="broadcasts" element={<Broadcasts />} /><Route path="broadcasts/:id" element={<BroadcastEditor />} /><Route path="chatbot" element={<Chatbot />} />
                <Route path="catalog" element={<Catalog />} /><Route path="rewards" element={<Rewards />} />
                <Route path="content" element={<ContentCalendar />} /><Route path="content/:id" element={<ContentEditor />} /><Route path="seo" element={<SeoTools />} />
                <Route path="ads" element={<AdsList />} /><Route path="ads/:id" element={<AdDetail />} /><Route path="analytics" element={<Analytics />} />
                <Route path="links" element={<Links />} /><Route path="links/:id" element={<LinkDetail />} />
                <Route path="pages" element={<PagesList />} /><Route path="pages/:id" element={<PageEditor />} /><Route path="apps" element={<Apps />} />
                <Route path="growth/offers" element={<OffersList />} /><Route path="growth/offers/:id" element={<OfferEditor />} /><Route path="growth/conversions" element={<Conversions />} />
                <Route path="settings/business" element={<BusinessProfile />} /><Route path="settings/team" element={<Team />} /><Route path="settings/billing" element={<Billing />} />
                <Route path="settings/profile" element={<Account />} /><Route path="settings/clients" element={<AgencyClients />} /><Route path="settings/domains" element={<Domains />} /><Route path="settings/api" element={<ApiKeys />} />
                <Route path="support" element={<Tickets />} /><Route path="support/:id" element={<TicketThread />} />
              </Route>
            </Route>

            <Route element={<Guard allow="promoter" />}>
              <Route path="earn" element={<PromoterShell />}>
                <Route index element={<EarnDashboard />} /><Route path="offers" element={<OfferWall />} /><Route path="history" element={<Earnings />} />
                <Route path="payouts" element={<Payouts />} /><Route path="withdraw" element={<Navigate to="/earn/payouts" replace />} /><Route path="kyc" element={<Kyc />} />
                <Route path="profile" element={<Account />} /><Route path="support" element={<Tickets />} /><Route path="support/:id" element={<TicketThread />} />
              </Route>
            </Route>

            <Route element={<Guard allow="admin" />}>
              <Route path="admin" element={<AdminShell />}>
                <Route index element={<AdminHome />} /><Route path="workspaces" element={<AdminWorkspaces />} /><Route path="finance" element={<Finance />} />
                <Route path="rates" element={<AdminRatesPlans />} /><Route path="system" element={<AdminSystem />} />
                <Route path="tickets" element={<Tickets staff />} /><Route path="tickets/:id" element={<TicketThread staff />} />
              </Route>
            </Route>
            <Route path="*" element={<NotFound />} />
          </Routes>
          <Toaster />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
