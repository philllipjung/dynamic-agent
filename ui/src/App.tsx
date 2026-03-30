import React, { useState } from 'react';
import './App.css';
import Tab1 from './components/Tab1';
import Tab2 from './components/Tab2';
import Tab3 from './components/Tab3';

function App() {
  const [activeTab, setActiveTab] = useState<'tab1' | 'tab2' | 'tab3'>('tab1');

  return (
    <div className="min-h-screen bg-white">
      {/* Header */}
      <header className="bg-black text-white shadow-lg">
        <div className="max-w-7xl mx-auto px-4 py-4">
          <h1 className="text-2xl font-bold">Java Agent Dashboard</h1>
          <p className="text-gray-200 text-sm">Runtime Instrumentation & Analysis</p>
        </div>
      </header>

      {/* Tabs */}
      <div className="max-w-7xl mx-auto px-4 mt-6">
        <div className="bg-white rounded-lg shadow border border-black">
          <div className="border-b border-black">
            <nav className="flex space-x-8 px-4" aria-label="Tabs">
              <button
                onClick={() => setActiveTab('tab1')}
                className={`${
                  activeTab === 'tab1'
                    ? 'border-black text-black border-b-2'
                    : 'border-transparent text-gray-600 hover:text-black'
                } whitespace-nowrap py-4 px-1 font-medium text-sm`}
              >
                Span & Link
              </button>
              <button
                onClick={() => setActiveTab('tab2')}
                className={`${
                  activeTab === 'tab2'
                    ? 'border-black text-black border-b-2'
                    : 'border-transparent text-gray-600 hover:text-black'
                } whitespace-nowrap py-4 px-1 font-medium text-sm`}
              >
                Event Capturing
              </button>
              <button
                onClick={() => setActiveTab('tab3')}
                className={`${
                  activeTab === 'tab3'
                    ? 'border-black text-black border-b-2'
                    : 'border-transparent text-gray-600 hover:text-black'
                } whitespace-nowrap py-4 px-1 font-medium text-sm`}
              >
                Arthas Analysis
              </button>
            </nav>
          </div>

          {/* Tab Content */}
          <div className="p-6">
            {activeTab === 'tab1' && <Tab1 />}
            {activeTab === 'tab2' && <Tab2 />}
            {activeTab === 'tab3' && <Tab3 />}
          </div>
        </div>
      </div>

      {/* Footer */}
      <footer className="max-w-7xl mx-auto px-4 py-6 text-center text-gray-600 text-sm">
        <p>Java Agent System v1.0.0 | Powered by ByteBuddy & Arthas</p>
      </footer>
    </div>
  );
}

export default App;
