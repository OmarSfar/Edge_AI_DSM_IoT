# Resilient Edge AI & Cloud Demand Side Management (DSM) System

An end-to-end, resilient **Demand Side Management (DSM)** IoT application for smart building energy monitoring and peak shaving. The system combines **Edge Machine Learning** on resource-constrained microcontrollers (Nordic nRF52840), **CoAP-based M2M/Cloud communication**, a central **Java Californium Cloud Controller**, time-series telemetry in **InfluxDB**, and real-time visualization via **Grafana**.

Developed for the *Internet of Things* course (M.Sc. in Computer Engineering, University of Pisa — A.Y. 2025/2026).

---

## 📌 Use Case & Architecture Overview

In modern facilities, simultaneous activation of heavy electrical loads (HVAC, industrial equipment, EV chargers) causes sudden power surges.

This system employs a **two-tier closed-loop control system**:
1. **Micro-Level (Edge Layer):** IoT smart meters monitor power readings, run on-device neural network inference to predict power spikes (> 5.0 kW), immediately shed local loads, activate a hardware safety interlock (Red LED), and notify neighboring nodes directly via peer-to-peer CoAP (M2M) without waiting for the Cloud.
2. **Macro-Level (Cloud Layer):** A central server aggregates building-wide telemetry via CoAP Observe. If the aggregate predicted demand exceeds a baseline safety threshold (> 4.0 kW), the Cloud triggers automated load reduction commands across non-essential devices.

```
[ IoT Edge Nodes (nRF52840) ] <--- CoAP / UDP (M2M) ---> [ Neighbor Node ]
        |
   6LoWPAN / IPv6 (Border Router)
        |
        v
[ Java Cloud Application (Californium) ] <--- CLI User Interface
        |
        v
[ InfluxDB (Time-Series DB) ]
        |
        v
[ Grafana Dashboard ]
```

---

## 🛠️ Tech Stack

* **Edge Firmware:** [Contiki-NG](https://github.com/contiki-ng/contiki-ng) OS (C) targeting **Nordic nRF52840 Dongles** (`PCA10059`).
* **Edge Machine Learning:** TensorFlow / Keras trained in Python and exported to optimized C via [emlearn](https://github.com/emlearn/emlearn).
* **Protocols & Data Format:** CoAP over UDP with Observe (RFC 7641), lightweight JSON encoding.
* **Cloud Backend:** Java 11+ with Eclipse Californium framework.
* **Database & Monitoring:** InfluxDB (v2.x) and Grafana.

---

## 🧠 Edge Machine Learning Model

* **Architecture:** Artificial Neural Network (ANN) with 2 hidden Dense layers (16 neurons each, ReLU) and 1 linear output neuron.
* **Input Window:** Sliding temporal window of the last 5 power readings (t-4 ... t0) to predict the future 6th reading (t+1).
* **Performance & Memory Footprint:**
  * **MAE:** 0.16 kW on test partition.
  * **Memory:** Quantized C header (`power_predictor.h`) takes only **~5.8 KB**, using just ~2.2% of the 256 KB RAM available on the nRF52840.

---

## 🛡️ Resilience & Adaptive Mechanisms

The system was evaluated under stressful network and hardware fault conditions:

1. **Edge Forward-Fill Imputation (Sensor Glitches):** If a sensor generates an invalid reading (-999.0 kW), the faulty reading is sent to the Cloud to trigger alerts, but the edge node imputes the last valid reading into its local ML sliding window to prevent model math corruption.
2. **Cloud Dynamic Thresholding (Packet Loss & Node Outages):** The Cloud continuously tracks node heartbeats. If a node goes offline, the global safety threshold is dynamically lowered to operate in a conservative "Safe Mode".
3. **Finite State Machine & Safety Interlock:** Enforces clear operational states (`NORMAL_OFF`, `NORMAL_ON`, `CRITICAL_OFF`). Once local safety is tripped (`CRITICAL_OFF`), local hardware safety overrides any remote 'ON' command until the local fault clears.

---

## 📂 Dataset Information

The ML model was trained on the open **Individual Household Electric Power Consumption** dataset.

Due to GitHub's file size limits, the raw CSV dataset is not tracked in this repository:
* **Dataset Name:** `household_power_consumption.csv`
* **Source:** UCI Machine Learning Repository / Kaggle (Individual Household Electric Power Consumption)
* **Setup:** Download the dataset, extract the `.csv` file, and place it inside the `machine learning/` folder before running `train_model.ipynb`.

---

## 📁 Repository Structure

```
├── contiki-ng/            # Edge firmware in C (Contiki-NG, CoAP resources, FSM)
│   ├── server.c           # Main edge node application & local logic
│   └── power_predictor.h  # C header containing exported neural network weights
├── machine learning/      # Training pipeline & data processing
│   ├── train_model.ipynb  # Preprocessing, ANN training & emlearn export
│   └── Influx_queries.txt # Flux queries used for Grafana dashboards
├── cloud-app/             # Java Californium Cloud application
│   ├── src/               # Ingestion pipeline, Observe handlers, CLI & FSM
│   └── config.txt         # Static device IP configuration
└── docs/                  # Project documentation & test reports
```

---

## 👤 Author

**Omar Tomas Sfar**  
Master's Degree in Computer Engineering — University of Pisa
