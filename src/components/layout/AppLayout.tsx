import { Outlet } from "react-router-dom";
import { AppSidebar } from "./AppSidebar";
import { TopBar } from "./TopBar";
import { useTradeEvents } from "@/hooks/useTradeEvents";
import { KillSwitchModal } from "@/components/trading/KillSwitchModal";

export function AppLayout() {
  useTradeEvents(); // Live WS → React Query cache invalidation

  return (
    <div className="flex min-h-screen bg-background">
      <AppSidebar />
      <div className="flex-1 ml-56">
        <TopBar />
        <main className="p-6">
          <Outlet />
        </main>
      </div>
      <KillSwitchModal />
    </div>
  );
}
