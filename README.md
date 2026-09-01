# Resilient Edge AI & Cloud Demand Side Management (DSM) System

An end-to-end, resilient **Demand Side Management (DSM)** IoT application for smart building energy monitoring and peak shaving. The system combines **Edge Machine Learning** on resource-constrained microcontrollers (Nordic nRF52840), **CoAP-based M2M/Cloud communication**, a central **Java Californium Cloud Controller**, time-series telemetry in **InfluxDB**, and real-time visualization via **Grafana**.

Developed for the *Internet of Things* course (M.Sc. in Computer Engineering, University of Pisa — A.Y. 2025/2026).

---

## 📌 Use Case & Architecture Overview

In modern facilities, simultaneous activation of heavy electrical loads (HVAC, industrial equipment, EV chargers) causes sudden power surges.

This system employs a **two-tier closed-loop control system**:
1. **Micro-Level (Edge Layer):** IoT smart meters monitor power readings, run on-device neural network inference to predict power spikes, immediately shed local loads, activate a hardware safety interlock (Red LED), and notify neighboring nodes directly via peer-to-peer CoAP (M2M) without waiting for the Cloud.
2. **Macro-Level (Cloud Layer):** A central server aggregates building-wide telemetry via CoAP Observe. If the aggregate predicted demand exceeds a baseline safety threshold, the Cloud triggers automated load reduction commands across non-essential devices.

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

* **Dataset Name:** `household_power_consumption.csv`
* **Source:** https://www.kaggle.com/datasets/uciml/electric-power-consumption-data-set
* **Setup:** Download the dataset, extract the `.csv` file, and place it inside the `machine learning/` folder before running `train_model.ipynb`.

---

## 📁 Repository Structure

```
├── Cloud-application/
│   ├── src/main/java/
│   │   ├── CloudAppDSM.java             # Main cloud entry point, CoAP Observe & FSM control loop
│   │   └── DatabaseManager.java         # InfluxDB client & asynchronous ingestion service
│   ├── cloud-config.properties.txt      # Thresholds & cloud-specific runtime parameters
│   ├── config.txt                       # Static IPv6 node directory configuration
│   └── pom.xml                          # Maven build file (dependencies for Californium & InfluxDB)
│
├── Contiki/
│   ├── resources/
│   │   ├── res-load.c                   # CoAP actuator resource (/load) for load switching
│   │   └── res-power.c                  # Observable CoAP sensor resource (/power) in JSON
│   ├── data_trace.h                     # Power dataset traces for edge emulation
│   ├── power_predictor.h                # C header containing exported ANN weights (emlearn)
│   ├── project-conf.h                   # Contiki-NG network stack & buffer configurations
│   ├── sensor.c                         # Edge node main process, button ISR & fail-safe logic
│   └── Makefile                         # Contiki-NG build automation
│
├── machine learning/
│   └── train_model.ipynb                # Data exploration, scaling, ANN training & C export
│             
├── Influx_queries.txt                   # Flux queries configured on Grafana dashboards
└── Documentation.pdf                    # Documentation of the project
```


---

## 👤 Author

**Omar Tomas Sfar**  
Master's Degree in Computer Engineering — University of Pisa
