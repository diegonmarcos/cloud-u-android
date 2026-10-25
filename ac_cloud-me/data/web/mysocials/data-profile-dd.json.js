// GENERATED FROM profile-dd.json by front-data-json-js-wrapper.sh — DO NOT EDIT BY HAND.
// Re-generate with: bash a_Portals/linktree/src/data/build.sh
(function () {
  var g = (typeof globalThis !== "undefined") ? globalThis : (typeof window !== "undefined" ? window : this);
  g.PORTAL_DATA = g.PORTAL_DATA || {};
  g.PORTAL_DATA["profile-dd"] = {
 "id": "dd",
 "title": "Due diligence",
 "version": 1,
 "_doc": "Schema only: structure and labels, no values. The owner's values live in the private store (cloud-me-data-pvt, profiles/dd.data.json) and reach this page only through the Cloud Me app bridge after sign-in. Never put a values file in this repo.",
 "sections": [
  {
   "id": "identity",
   "title": "Identity",
   "fields": [
    {
     "id": "identity.full_name",
     "label": "Full legal name"
    },
    {
     "id": "identity.date_of_birth",
     "label": "Date of birth"
    },
    {
     "id": "identity.place_of_birth",
     "label": "Place of birth"
    },
    {
     "id": "identity.nationalities",
     "label": "Nationalities"
    },
    {
     "id": "identity.brazilian_passport",
     "label": "Brazilian passport (number, expiry)"
    },
    {
     "id": "identity.spanish_passport",
     "label": "Spanish passport (number, expiry)"
    },
    {
     "id": "identity.cpf",
     "label": "CPF (Brazil)"
    },
    {
     "id": "identity.nie_dni",
     "label": "NIE / DNI (Spain)"
    },
    {
     "id": "identity.german_residence",
     "label": "German residence title"
    },
    {
     "id": "identity.tax_ids",
     "label": "Other tax identification numbers"
    }
   ]
  },
  {
   "id": "address_history",
   "title": "Address history",
   "fields": [
    {
     "id": "address_history.current",
     "label": "Current address"
    },
    {
     "id": "address_history.previous_1",
     "label": "Previous address 1"
    },
    {
     "id": "address_history.previous_2",
     "label": "Previous address 2"
    },
    {
     "id": "address_history.proof_of_address",
     "label": "Proof of address documents"
    }
   ]
  },
  {
   "id": "education_and_professional_verification",
   "title": "Education and professional verification",
   "fields": [
    {
     "id": "education_and_professional_verification.education",
     "label": "Education (degree, institution, year)"
    },
    {
     "id": "education_and_professional_verification.certifications",
     "label": "Certifications and licences"
    },
    {
     "id": "education_and_professional_verification.employment_history",
     "label": "Employment history"
    },
    {
     "id": "education_and_professional_verification.current_role",
     "label": "Current role"
    },
    {
     "id": "education_and_professional_verification.employment_verification",
     "label": "Employment verification letters"
    },
    {
     "id": "education_and_professional_verification.professional_references",
     "label": "Professional references"
    }
   ]
  },
  {
   "id": "corporate_affiliations_and_ubo",
   "title": "Corporate affiliations and UBO",
   "fields": [
    {
     "id": "corporate_affiliations_and_ubo.directorships",
     "label": "Directorships"
    },
    {
     "id": "corporate_affiliations_and_ubo.shareholdings",
     "label": "Shareholdings"
    },
    {
     "id": "corporate_affiliations_and_ubo.ubo_declarations",
     "label": "Ultimate beneficial owner declarations"
    },
    {
     "id": "corporate_affiliations_and_ubo.company_registry_extracts",
     "label": "Company registry extracts"
    },
    {
     "id": "corporate_affiliations_and_ubo.power_of_attorney",
     "label": "Powers of attorney"
    }
   ]
  },
  {
   "id": "civil_litigation",
   "title": "Civil litigation",
   "fields": [
    {
     "id": "civil_litigation.civil_cases",
     "label": "Civil cases (as party)"
    },
    {
     "id": "civil_litigation.judgments",
     "label": "Judgments and liens"
    },
    {
     "id": "civil_litigation.arbitration",
     "label": "Arbitration"
    },
    {
     "id": "civil_litigation.search_scope",
     "label": "Courts and registries searched"
    }
   ]
  },
  {
   "id": "criminal_record_certificates",
   "title": "Criminal record certificates",
   "fields": [
    {
     "id": "criminal_record_certificates.brazil",
     "label": "Brazil: antecedentes criminais"
    },
    {
     "id": "criminal_record_certificates.spain",
     "label": "Spain: certificado de antecedentes penales"
    },
    {
     "id": "criminal_record_certificates.germany",
     "label": "Germany: Fuehrungszeugnis"
    },
    {
     "id": "criminal_record_certificates.other_countries",
     "label": "Other countries of residence"
    },
    {
     "id": "criminal_record_certificates.apostille",
     "label": "Apostille / legalisation"
    }
   ]
  },
  {
   "id": "tax_compliance",
   "title": "Tax compliance",
   "fields": [
    {
     "id": "tax_compliance.receita_federal",
     "label": "Receita Federal (Brazil) status"
    },
    {
     "id": "tax_compliance.agencia_tributaria",
     "label": "Agencia Tributaria (Spain) status"
    },
    {
     "id": "tax_compliance.finanzamt",
     "label": "Finanzamt (Germany) status"
    },
    {
     "id": "tax_compliance.tax_residency",
     "label": "Tax residency"
    },
    {
     "id": "tax_compliance.tax_residency_certificate",
     "label": "Tax residency certificates"
    },
    {
     "id": "tax_compliance.tax_returns",
     "label": "Latest tax returns filed"
    }
   ]
  },
  {
   "id": "credit_and_insolvency",
   "title": "Credit and insolvency",
   "fields": [
    {
     "id": "credit_and_insolvency.credit_report",
     "label": "Credit report"
    },
    {
     "id": "credit_and_insolvency.schufa",
     "label": "SCHUFA (Germany)"
    },
    {
     "id": "credit_and_insolvency.insolvency_registers",
     "label": "Insolvency register search"
    },
    {
     "id": "credit_and_insolvency.bankruptcy_history",
     "label": "Bankruptcy history"
    }
   ]
  },
  {
   "id": "pep_and_sanctions_screening",
   "title": "PEP and sanctions screening",
   "fields": [
    {
     "id": "pep_and_sanctions_screening.pep",
     "label": "PEP status (self and close associates)"
    },
    {
     "id": "pep_and_sanctions_screening.ofac",
     "label": "OFAC SDN list"
    },
    {
     "id": "pep_and_sanctions_screening.eu_sanctions",
     "label": "EU consolidated sanctions list"
    },
    {
     "id": "pep_and_sanctions_screening.un_sanctions",
     "label": "UN Security Council list"
    },
    {
     "id": "pep_and_sanctions_screening.screening_provider",
     "label": "Screening provider and date"
    }
   ]
  },
  {
   "id": "adverse_media",
   "title": "Adverse media",
   "fields": [
    {
     "id": "adverse_media.search_terms",
     "label": "Search terms and languages"
    },
    {
     "id": "adverse_media.findings",
     "label": "Findings"
    },
    {
     "id": "adverse_media.search_date",
     "label": "Date of search"
    }
   ]
  },
  {
   "id": "source_of_wealth_and_funds",
   "title": "Source of wealth and funds",
   "fields": [
    {
     "id": "source_of_wealth_and_funds.source_of_wealth",
     "label": "Source of wealth"
    },
    {
     "id": "source_of_wealth_and_funds.source_of_funds",
     "label": "Source of funds for this investment"
    },
    {
     "id": "source_of_wealth_and_funds.supporting_documents",
     "label": "Supporting documents"
    },
    {
     "id": "source_of_wealth_and_funds.bank_references",
     "label": "Bank references"
    }
   ]
  },
  {
   "id": "aml_risk_rating",
   "title": "AML risk rating",
   "fields": [
    {
     "id": "aml_risk_rating.risk_rating",
     "label": "Overall risk rating"
    },
    {
     "id": "aml_risk_rating.rationale",
     "label": "Rationale"
    },
    {
     "id": "aml_risk_rating.review_date",
     "label": "Next review date"
    },
    {
     "id": "aml_risk_rating.reviewer",
     "label": "Reviewer"
    }
   ]
  },
  {
   "id": "references",
   "title": "References",
   "fields": [
    {
     "id": "references.reference_1",
     "label": "Reference 1"
    },
    {
     "id": "references.reference_2",
     "label": "Reference 2"
    },
    {
     "id": "references.reference_3",
     "label": "Reference 3"
    }
   ]
  },
  {
   "id": "document_checklist",
   "title": "Document checklist",
   "fields": [
    {
     "id": "document_checklist.passport_copies",
     "label": "Passport copies"
    },
    {
     "id": "document_checklist.proof_of_address_doc",
     "label": "Proof of address"
    },
    {
     "id": "document_checklist.criminal_certificates",
     "label": "Criminal record certificates"
    },
    {
     "id": "document_checklist.tax_certificates",
     "label": "Tax compliance certificates"
    },
    {
     "id": "document_checklist.company_extracts",
     "label": "Company registry extracts"
    },
    {
     "id": "document_checklist.wealth_evidence",
     "label": "Source of wealth evidence"
    },
    {
     "id": "document_checklist.signed_declaration",
     "label": "Signed shareholder declaration"
    }
   ]
  },
  {
   "id": "red_flag_summary",
   "title": "Red-flag summary",
   "fields": [
    {
     "id": "red_flag_summary.red_flags",
     "label": "Red flags identified"
    },
    {
     "id": "red_flag_summary.mitigants",
     "label": "Mitigants"
    },
    {
     "id": "red_flag_summary.open_items",
     "label": "Open items before approval"
    }
   ]
  }
 ]
}
;
})();
