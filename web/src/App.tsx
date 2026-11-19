import React from 'react';
import { useSession } from './lib/session';
import { UnlockScreen } from './components/UnlockScreen';
import { Layout } from './components/Layout';
import { OverviewView } from './views/OverviewView';
import { AnalyticsView } from './views/AnalyticsView';
import { TransactionsView } from './views/TransactionsView';
import { AssetsView } from './views/AssetsView';
import { AuditView } from './views/AuditView';
import { BackupsView } from './views/BackupsView';
import { ConnectDeviceView } from './views/ConnectDeviceView';
import { SettingsView } from './views/SettingsView';

const ActiveView: React.FC = () => {
  const { activeView } = useSession();

  switch (activeView) {
    case 'overview':
      return <OverviewView />;
    case 'analytics':
      return <AnalyticsView />;
    case 'transactions':
      return <TransactionsView />;
    case 'assets':
      return <AssetsView />;
    case 'audit':
      return <AuditView />;
    case 'backups':
      return <BackupsView />;
    case 'connect':
      return <ConnectDeviceView />;
    case 'settings':
      return <SettingsView />;
    default:
      return <OverviewView />;
  }
};

export const App: React.FC = () => {
  const { isUnlocked, isRestoring } = useSession();
  if (!isUnlocked) {
    if (isRestoring) {
      return (
        <div className="min-h-screen mm-bg-canvas grid place-items-center">
          <div className="text-sm mm-text-secondary tabular-nums">正在恢复会话…</div>
        </div>
      );
    }
    return <UnlockScreen />;
  }
  return (
    <Layout>
      <ActiveView />
    </Layout>
  );
};
