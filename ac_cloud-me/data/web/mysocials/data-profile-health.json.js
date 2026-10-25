// GENERATED FROM profile-health.json by front-data-json-js-wrapper.sh — DO NOT EDIT BY HAND.
// Re-generate with: bash a_Portals/linktree/src/data/build.sh
(function () {
  var g = (typeof globalThis !== "undefined") ? globalThis : (typeof window !== "undefined" ? window : this);
  g.PORTAL_DATA = g.PORTAL_DATA || {};
  g.PORTAL_DATA["profile-health"] = {
 "id": "health",
 "title": "Health",
 "version": 1,
 "_doc": "Schema only: structure and labels, no values. The owner's values live in the private store (cloud-me-data-pvt, profiles/health.data.json) and reach this page only through the Cloud Me app bridge after sign-in. Never put a values file in this repo.",
 "sections": [
  {
   "id": "identity_and_emergency_contacts",
   "title": "Identity and emergency contacts",
   "fields": [
    {
     "id": "identity_and_emergency_contacts.full_name",
     "label": "Full name"
    },
    {
     "id": "identity_and_emergency_contacts.date_of_birth",
     "label": "Date of birth"
    },
    {
     "id": "identity_and_emergency_contacts.sex_at_birth",
     "label": "Sex at birth"
    },
    {
     "id": "identity_and_emergency_contacts.nationalities",
     "label": "Nationalities"
    },
    {
     "id": "identity_and_emergency_contacts.preferred_language",
     "label": "Preferred language"
    },
    {
     "id": "identity_and_emergency_contacts.home_city",
     "label": "Home city"
    },
    {
     "id": "identity_and_emergency_contacts.emergency_contact_1",
     "label": "Emergency contact 1 (name, relation, phone)"
    },
    {
     "id": "identity_and_emergency_contacts.emergency_contact_2",
     "label": "Emergency contact 2"
    },
    {
     "id": "identity_and_emergency_contacts.health_proxy",
     "label": "Health care proxy"
    },
    {
     "id": "identity_and_emergency_contacts.primary_physician",
     "label": "Primary physician"
    }
   ]
  },
  {
   "id": "insurance",
   "title": "Insurance",
   "fields": [
    {
     "id": "insurance.provider",
     "label": "Insurer"
    },
    {
     "id": "insurance.policy_number",
     "label": "Policy number"
    },
    {
     "id": "insurance.coverage",
     "label": "Coverage type"
    },
    {
     "id": "insurance.valid_until",
     "label": "Valid until"
    },
    {
     "id": "insurance.pre_authorisation",
     "label": "Pre-authorisation for surgery"
    }
   ]
  },
  {
   "id": "allergies",
   "title": "Allergies",
   "fields": [
    {
     "id": "allergies.drug",
     "label": "Drug allergies"
    },
    {
     "id": "allergies.food",
     "label": "Food allergies"
    },
    {
     "id": "allergies.latex",
     "label": "Latex allergy"
    },
    {
     "id": "allergies.contrast",
     "label": "Contrast media"
    },
    {
     "id": "allergies.environmental",
     "label": "Environmental"
    },
    {
     "id": "allergies.reaction_types",
     "label": "Reaction types and severity"
    }
   ]
  },
  {
   "id": "current_medications_and_supplements",
   "title": "Current medications and supplements",
   "fields": [
    {
     "id": "current_medications_and_supplements.prescriptions",
     "label": "Prescriptions (dose, timing)"
    },
    {
     "id": "current_medications_and_supplements.over_the_counter",
     "label": "Over the counter"
    },
    {
     "id": "current_medications_and_supplements.supplements",
     "label": "Supplements (dose, timing)"
    },
    {
     "id": "current_medications_and_supplements.anticoagulants",
     "label": "Anticoagulants or antiplatelets"
    },
    {
     "id": "current_medications_and_supplements.last_reviewed",
     "label": "Last medication reconciliation"
    }
   ]
  },
  {
   "id": "problem_list_and_history",
   "title": "Problem list and history",
   "fields": [
    {
     "id": "problem_list_and_history.medical",
     "label": "Medical history"
    },
    {
     "id": "problem_list_and_history.surgical",
     "label": "Surgical history"
    },
    {
     "id": "problem_list_and_history.family",
     "label": "Family history"
    },
    {
     "id": "problem_list_and_history.hospitalisations",
     "label": "Hospitalisations"
    },
    {
     "id": "problem_list_and_history.alcohol",
     "label": "Alcohol"
    },
    {
     "id": "problem_list_and_history.tobacco",
     "label": "Tobacco"
    },
    {
     "id": "problem_list_and_history.substances",
     "label": "Other substances"
    },
    {
     "id": "problem_list_and_history.occupation",
     "label": "Occupation"
    },
    {
     "id": "problem_list_and_history.travel",
     "label": "Travel history"
    }
   ]
  },
  {
   "id": "immunisations",
   "title": "Immunisations",
   "fields": [
    {
     "id": "immunisations.tetanus",
     "label": "Tetanus"
    },
    {
     "id": "immunisations.hepatitis_b",
     "label": "Hepatitis B"
    },
    {
     "id": "immunisations.mmr",
     "label": "MMR"
    },
    {
     "id": "immunisations.covid_19",
     "label": "COVID-19"
    },
    {
     "id": "immunisations.influenza",
     "label": "Influenza"
    },
    {
     "id": "immunisations.other",
     "label": "Other vaccines"
    }
   ]
  },
  {
   "id": "vitals_and_anthropometrics",
   "title": "Vitals and anthropometrics",
   "fields": [
    {
     "id": "vitals_and_anthropometrics.height",
     "label": "Height"
    },
    {
     "id": "vitals_and_anthropometrics.weight",
     "label": "Weight"
    },
    {
     "id": "vitals_and_anthropometrics.bmi",
     "label": "BMI"
    },
    {
     "id": "vitals_and_anthropometrics.blood_pressure",
     "label": "Blood pressure"
    },
    {
     "id": "vitals_and_anthropometrics.resting_heart_rate",
     "label": "Resting heart rate"
    },
    {
     "id": "vitals_and_anthropometrics.spo2",
     "label": "SpO2"
    },
    {
     "id": "vitals_and_anthropometrics.temperature",
     "label": "Temperature"
    },
    {
     "id": "vitals_and_anthropometrics.cloud_me_health",
     "label": "Cloud Me Health data (live link)"
    }
   ]
  },
  {
   "id": "laboratory_results",
   "title": "Laboratory results",
   "fields": [
    {
     "id": "laboratory_results.cbc",
     "label": "CBC (Hb, WBC, platelets)"
    },
    {
     "id": "laboratory_results.cmp",
     "label": "CMP (creatinine, electrolytes, liver enzymes)"
    },
    {
     "id": "laboratory_results.pt_inr",
     "label": "Coagulation PT/INR"
    },
    {
     "id": "laboratory_results.aptt",
     "label": "aPTT"
    },
    {
     "id": "laboratory_results.hba1c",
     "label": "HbA1c"
    },
    {
     "id": "laboratory_results.lipids",
     "label": "Lipid panel"
    },
    {
     "id": "laboratory_results.ferritin",
     "label": "Ferritin"
    },
    {
     "id": "laboratory_results.tsh",
     "label": "TSH"
    },
    {
     "id": "laboratory_results.vitamin_d",
     "label": "Vitamin D"
    },
    {
     "id": "laboratory_results.vitamin_b12",
     "label": "Vitamin B12"
    },
    {
     "id": "laboratory_results.crp",
     "label": "CRP"
    },
    {
     "id": "laboratory_results.urinalysis",
     "label": "Urinalysis"
    },
    {
     "id": "laboratory_results.pregnancy_test",
     "label": "Pregnancy test (if applicable)"
    },
    {
     "id": "laboratory_results.reference_ranges",
     "label": "Reference ranges and lab used"
    }
   ]
  },
  {
   "id": "cardiology",
   "title": "Cardiology",
   "fields": [
    {
     "id": "cardiology.ecg",
     "label": "ECG"
    },
    {
     "id": "cardiology.echocardiogram",
     "label": "Echocardiogram"
    },
    {
     "id": "cardiology.stress_test",
     "label": "Stress test"
    },
    {
     "id": "cardiology.cardiac_history",
     "label": "Cardiac history"
    }
   ]
  },
  {
   "id": "pulmonary",
   "title": "Pulmonary",
   "fields": [
    {
     "id": "pulmonary.spirometry",
     "label": "Spirometry"
    },
    {
     "id": "pulmonary.chest_xray",
     "label": "Chest X-ray"
    },
    {
     "id": "pulmonary.sleep_apnoea",
     "label": "Sleep apnoea history"
    },
    {
     "id": "pulmonary.asthma_copd",
     "label": "Asthma / COPD"
    }
   ]
  },
  {
   "id": "imaging",
   "title": "Imaging",
   "fields": [
    {
     "id": "imaging.recent_imaging",
     "label": "Recent imaging studies"
    },
    {
     "id": "imaging.relevant_to_procedure",
     "label": "Imaging relevant to the procedure"
    }
   ]
  },
  {
   "id": "anesthesia_and_airway",
   "title": "Anesthesia and airway",
   "fields": [
    {
     "id": "anesthesia_and_airway.previous_anesthesia",
     "label": "Previous anesthesia and complications"
    },
    {
     "id": "anesthesia_and_airway.family_anesthesia_problems",
     "label": "Family anesthesia problems (e.g. malignant hyperthermia)"
    },
    {
     "id": "anesthesia_and_airway.mallampati",
     "label": "Mallampati class"
    },
    {
     "id": "anesthesia_and_airway.thyromental_distance",
     "label": "Thyromental distance"
    },
    {
     "id": "anesthesia_and_airway.mouth_opening",
     "label": "Mouth opening"
    },
    {
     "id": "anesthesia_and_airway.neck_mobility",
     "label": "Neck mobility"
    },
    {
     "id": "anesthesia_and_airway.asa_class",
     "label": "ASA physical status class"
    },
    {
     "id": "anesthesia_and_airway.fasting_instructions",
     "label": "Fasting instructions"
    }
   ]
  },
  {
   "id": "pre_operative_risk_scores",
   "title": "Pre-operative risk scores",
   "fields": [
    {
     "id": "pre_operative_risk_scores.rcri",
     "label": "RCRI (cardiac risk)"
    },
    {
     "id": "pre_operative_risk_scores.stop_bang",
     "label": "STOP-BANG (sleep apnoea)"
    },
    {
     "id": "pre_operative_risk_scores.caprini",
     "label": "Caprini (VTE risk)"
    },
    {
     "id": "pre_operative_risk_scores.frailty",
     "label": "Frailty screen"
    },
    {
     "id": "pre_operative_risk_scores.functional_capacity",
     "label": "Functional capacity (METs)"
    }
   ]
  },
  {
   "id": "blood_type",
   "title": "Blood type",
   "fields": [
    {
     "id": "blood_type.abo_rh",
     "label": "ABO / Rh"
    },
    {
     "id": "blood_type.antibody_screen",
     "label": "Antibody screen"
    },
    {
     "id": "blood_type.transfusion_consent",
     "label": "Transfusion preferences"
    }
   ]
  },
  {
   "id": "advance_directives_and_consents",
   "title": "Advance directives and consents",
   "fields": [
    {
     "id": "advance_directives_and_consents.living_will",
     "label": "Living will / advance directive"
    },
    {
     "id": "advance_directives_and_consents.organ_donation",
     "label": "Organ donation"
    },
    {
     "id": "advance_directives_and_consents.surgical_consent",
     "label": "Surgical consent"
    },
    {
     "id": "advance_directives_and_consents.anesthesia_consent",
     "label": "Anesthesia consent"
    },
    {
     "id": "advance_directives_and_consents.code_status",
     "label": "Code status"
    }
   ]
  },
  {
   "id": "devices_and_implants",
   "title": "Devices and implants",
   "fields": [
    {
     "id": "devices_and_implants.implants",
     "label": "Implants"
    },
    {
     "id": "devices_and_implants.pacemaker",
     "label": "Pacemaker / ICD"
    },
    {
     "id": "devices_and_implants.prosthetics",
     "label": "Prosthetics"
    },
    {
     "id": "devices_and_implants.other_devices",
     "label": "Other devices"
    }
   ]
  },
  {
   "id": "dental",
   "title": "Dental",
   "fields": [
    {
     "id": "dental.last_visit",
     "label": "Last dental visit"
    },
    {
     "id": "dental.crowns_bridges",
     "label": "Crowns, bridges, removable work"
    },
    {
     "id": "dental.loose_teeth",
     "label": "Loose teeth or airway risk"
    }
   ]
  },
  {
   "id": "mental_health",
   "title": "Mental health",
   "fields": [
    {
     "id": "mental_health.conditions",
     "label": "Conditions"
    },
    {
     "id": "mental_health.treatment",
     "label": "Treatment"
    },
    {
     "id": "mental_health.screening",
     "label": "Screening (PHQ-9, GAD-7)"
    }
   ]
  },
  {
   "id": "documents_and_attachments",
   "title": "Documents and attachments",
   "fields": [
    {
     "id": "documents_and_attachments.reports",
     "label": "Medical reports"
    },
    {
     "id": "documents_and_attachments.imaging_files",
     "label": "Imaging files"
    },
    {
     "id": "documents_and_attachments.lab_pdfs",
     "label": "Lab PDFs"
    },
    {
     "id": "documents_and_attachments.consent_forms",
     "label": "Signed consent forms"
    }
   ]
  },
  {
   "id": "timeline",
   "title": "Timeline",
   "fields": [
    {
     "id": "timeline.key_events",
     "label": "Key medical events (dated)"
    },
    {
     "id": "timeline.upcoming",
     "label": "Upcoming appointments and procedures"
    }
   ]
  }
 ]
}
;
})();
