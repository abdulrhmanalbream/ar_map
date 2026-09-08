import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import { LocateFixed, MapPin, Maximize2, RefreshCw } from 'lucide-react';
import type { Device } from './types';
import { ageMs, isOffline, LOCATION_STALE_MS, number, sharedLocation } from './utils';

export default function LocationMap({ devices, selectedId, onSelect }: {
  devices: Device[]; selectedId: string | null; onSelect: (id: string) => void;
}) {
  const element = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const layer = useRef<L.LayerGroup | null>(null);
  const onSelectRef = useRef(onSelect);
  const initialFit = useRef(false);
  const [tileError, setTileError] = useState(false);
  const tiles = useRef<L.TileLayer | null>(null);
  onSelectRef.current = onSelect;
  const located = devices.filter(device => sharedLocation(device));

  useEffect(() => {
    if (!element.current) return;
    const instance = L.map(element.current, { zoomControl: false, scrollWheelZoom: false }).setView([21.4225, 39.8262], 15);
    map.current = instance;
    L.control.zoom({ position: 'bottomleft' }).addTo(instance);
    tiles.current = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors',
    }).addTo(instance);
    tiles.current.on('tileerror', () => setTileError(true));
    layer.current = L.layerGroup().addTo(instance);
    const resize = new ResizeObserver(() => instance.invalidateSize());
    resize.observe(element.current);
    return () => { resize.disconnect(); instance.remove(); map.current = null; layer.current = null; };
  }, []);

  useEffect(() => {
    if (!layer.current || !map.current) return;
    layer.current.clearLayers();
    const points: L.LatLngTuple[] = [];
    for (const device of devices) {
      const location = sharedLocation(device);
      if (!location) continue;
      const point: L.LatLngTuple = [location.lat, location.lng];
      points.push(point);
      const stale = ageMs(location.recordedAt) > LOCATION_STALE_MS || isOffline(device);
      const state = device.status === 'needs_help' ? 'help' : stale ? 'stale' : 'active';
      const selected = selectedId === device.id;
      const icon = L.divIcon({
        className: 'map-member',
        html: `<span class="map-marker ${state}${selected ? ' selected' : ''}"><span></span></span>`,
        iconSize: [34, 34], iconAnchor: [17, 17],
      });
      const marker = L.marker(point, { icon, title: device.name, keyboard: true }).addTo(layer.current);
      const label = document.createElement('span');
      label.textContent = device.name;
      marker.bindTooltip(label, { direction: 'top', offset: [0, -14] });
      marker.on('click', () => onSelectRef.current(device.id));
      if (selected && Number.isFinite(location.accuracyM) && location.accuracyM >= 0) {
        L.circle(point, { radius: Math.min(location.accuracyM, 100_000), color: '#18675a', weight: 1, fillOpacity: 0.08, interactive: false }).addTo(layer.current);
      }
    }
    if (points.length && !initialFit.current) {
      map.current.fitBounds(L.latLngBounds(points), { padding: [60, 60], maxZoom: 16 });
      initialFit.current = true;
    }
  }, [devices, selectedId]);

  useEffect(() => {
    const selected = devices.find(device => device.id === selectedId);
    const point = selected && sharedLocation(selected);
    if (point) map.current?.setView([point.lat, point.lng], Math.max(map.current.getZoom(), 16), { animate: false });
  }, [selectedId]); // Selection moves the viewport; polling preserves the operator's chosen view.

  const fitAll = () => {
    const points = located.map(device => { const p = sharedLocation(device)!; return [p.lat, p.lng] as L.LatLngTuple; });
    if (points.length) map.current?.fitBounds(L.latLngBounds(points), { padding: [50, 50], maxZoom: 16 });
  };

  return <section className="map-panel" aria-label="خريطة آخر المواقع المشتركة">
    <div className="map-heading"><div><MapPin size={18} /><h2>المتابعة على الخريطة</h2><span className="subtle">{number(located.length)} موقع مشترك</span></div><button className="icon-button" title="إظهار كل المواقع" aria-label="إظهار كل المواقع" onClick={fitAll} disabled={!located.length}><Maximize2 size={18} /></button></div>
    <div className="map-canvas"><div ref={element} className="leaflet-surface" />
      {!located.length && <div className="map-empty"><span className="empty-icon"><LocateFixed size={26} /></span><strong>بانتظار المواقع المشتركة</strong><p>تظهر المواقع هنا عندما يفعّل الأعضاء مشاركة الموقع من تطبيق سَراب.</p></div>}
      {tileError && <div className="map-error" role="status">تعذّر تحميل بعض أجزاء الخريطة.<button onClick={() => { setTileError(false); tiles.current?.redraw(); }}><RefreshCw size={14} /> إعادة المحاولة</button></div>}
    </div>
    <div className="map-footer"><div className="map-legend"><span><i className="dot active" />متصل</span><span><i className="dot help" />يحتاج مساعدة</span><span><i className="dot stale" />موقع قديم</span></div><span>آخر موقع فقط · الدائرة تمثل دقة الموقع</span></div>
  </section>;
}
