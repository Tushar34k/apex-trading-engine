import { Toaster } from "@/components/ui/toaster";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "@/contexts/AuthContext";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { AppLayout } from "@/components/layout/AppLayout";
import Index from "./pages/Index";
import Strategies from "./pages/Strategies";
import RiskControl from "./pages/RiskControl";
import Portfolio from "./pages/Portfolio";
import Backtesting from "./pages/Backtesting";
import PaperTrading from "./pages/PaperTrading";
import Analytics from "./pages/Analytics";
import ApiKeys from "./pages/ApiKeys";
import Admin from "./pages/Admin";
import Login from "./pages/Login";
import Register from "./pages/Register";
import NotFound from "./pages/NotFound";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 5000,
      refetchOnWindowFocus: false,
    },
  },
});

const App = () => (
  <QueryClientProvider client={queryClient}>
    <TooltipProvider>
      <Toaster />
      <Sonner />
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />
            <Route element={<ProtectedRoute />}>
              <Route element={<AppLayout />}>
                <Route path="/" element={<Index />} />
                <Route path="/strategies" element={<Strategies />} />
                <Route path="/risk" element={<RiskControl />} />
                <Route path="/portfolio" element={<Portfolio />} />
                <Route path="/backtesting" element={<Backtesting />} />
                <Route path="/paper-trading" element={<PaperTrading />} />
                <Route path="/analytics" element={<Analytics />} />
                <Route path="/api-keys" element={<ApiKeys />} />
                <Route path="/admin" element={<Admin />} />
              </Route>
            </Route>
            <Route path="*" element={<NotFound />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </TooltipProvider>
  </QueryClientProvider>
);

export default App;
