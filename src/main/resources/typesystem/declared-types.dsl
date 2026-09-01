// Declared once, here, as type-DSL text. TypePlatformCompiler parses this file and, from it
// alone, emits the PostgreSQL DDL, wires the generic REST surface, and (via GenerateClients)
// emits the typed Java and TypeScript clients under generated-clients/. Nothing downstream
// re-declares a field; every artifact in this subsystem traces back to this one file.

type Asset {
  name: STRING
  assetTag: STRING
  purchaseValue: NUMBER
  active: BOOLEAN
}

type Sensor {
  name: STRING
  unit: STRING
  lastReading: NUMBER
  calibrated: BOOLEAN
}

type WorkOrder {
  title: STRING
  priority: STRING
  estimatedHours: NUMBER
  closed: BOOLEAN
}

type Facility {
  name: STRING
  region: STRING
  squareFootage: NUMBER
  operational: BOOLEAN
}

type Equipment {
  model: STRING
  manufacturer: STRING
  ageYears: NUMBER
  underWarranty: BOOLEAN
}

type MaintenanceLog {
  summary: STRING
  technician: STRING
  hoursSpent: NUMBER
  resolved: BOOLEAN
}

type Invoice {
  invoiceNumber: STRING
  amount: NUMBER
  paid: BOOLEAN
}

type PurchaseOrder {
  poNumber: STRING
  quantity: NUMBER
  approved: BOOLEAN
}

type Vendor {
  name: STRING
  rating: NUMBER
  preferred: BOOLEAN
}

type Customer {
  name: STRING
  tier: STRING
  lifetimeValue: NUMBER
  active: BOOLEAN
}

type Shipment {
  trackingNumber: STRING
  weightKg: NUMBER
  delivered: BOOLEAN
}

type InventoryItem {
  sku: STRING
  quantityOnHand: NUMBER
  reorderNeeded: BOOLEAN
}

type Warehouse {
  name: STRING
  capacity: NUMBER
  climateControlled: BOOLEAN
}

type Employee {
  name: STRING
  title: STRING
  yearsOfService: NUMBER
  active: BOOLEAN
}

type Department {
  name: STRING
  headcount: NUMBER
  costCenter: STRING
}

type Project {
  name: STRING
  status: STRING
  budget: NUMBER
  onTrack: BOOLEAN
}

type Milestone {
  name: STRING
  dueInDays: NUMBER
  complete: BOOLEAN
}

type Budget {
  fiscalYear: STRING
  amount: NUMBER
  approved: BOOLEAN
}

type Expense {
  category: STRING
  amount: NUMBER
  reimbursed: BOOLEAN
}

type Contract {
  counterparty: STRING
  valueAmount: NUMBER
  active: BOOLEAN
}

type ServiceTicket {
  subject: STRING
  severity: STRING
  resolved: BOOLEAN
}

type Subscription {
  planName: STRING
  monthlyPrice: NUMBER
  autoRenew: BOOLEAN
}

type Product {
  name: STRING
  sku: STRING
  listPrice: NUMBER
  discontinued: BOOLEAN
}

type Category {
  name: STRING
  itemCount: NUMBER
}

type Supplier {
  name: STRING
  leadTimeDays: NUMBER
  certified: BOOLEAN
}

type Route {
  originCode: STRING
  destinationCode: STRING
  distanceKm: NUMBER
}

type Vehicle {
  vin: STRING
  mileage: NUMBER
  inService: BOOLEAN
}

type Driver {
  name: STRING
  licenseClass: STRING
  yearsExperience: NUMBER
}

type Delivery {
  orderId: STRING
  onTime: BOOLEAN
}

type QualityInspection {
  inspector: STRING
  passRate: NUMBER
  passed: BOOLEAN
}

type Defect {
  description: STRING
  severity: STRING
  resolved: BOOLEAN
}

type ProductionRun {
  batchNumber: STRING
  unitsProduced: NUMBER
  qualityChecked: BOOLEAN
}

type Machine {
  name: STRING
  throughputPerHour: NUMBER
  operational: BOOLEAN
}

type CalibrationRecord {
  instrumentName: STRING
  deviation: NUMBER
  withinTolerance: BOOLEAN
}

type Alert {
  message: STRING
  severity: STRING
  acknowledged: BOOLEAN
}

type Threshold {
  metricName: STRING
  limitValue: NUMBER
  enabled: BOOLEAN
}

type EnergyReading {
  source: STRING
  kilowattHours: NUMBER
  anomalous: BOOLEAN
}

type Meter {
  serialNumber: STRING
  lastReadValue: NUMBER
  active: BOOLEAN
}

type Site {
  name: STRING
  latitude: NUMBER
  longitude: NUMBER
  active: BOOLEAN
}

type ComplianceAudit {
  standard: STRING
  score: NUMBER
  passed: BOOLEAN
}
