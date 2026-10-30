package com.diegonmarcos.capitalfinterm

/** A trimmed real DBnomics answer for FED/H15 (Treasury constant maturities, nominal 10-year, business day): metadata dropped, the tail kept, one "NA" holiday. */
object DbnomicsH15Fixture {
    const val TEN_YEAR = """{"_meta":{"args":{"observations":"1","metadata":"0"},"version":"22.1.17"},"series":{"docs":[{"@frequency":"daily","dataset_code":"H15","dataset_name":"H.15 Selected Interest Rates","provider_code":"FED","series_code":"RIFLGFCY10_N.B","series_name":"Business day - U.S. government securities/Treasury constant maturities/Nominal - 10-year","period":["2026-10-22","2026-10-23","2026-10-26","2026-10-27","2026-10-28","2026-10-29"],"period_start_day":["2026-10-22","2026-10-23","2026-10-26","2026-10-27","2026-10-28","2026-10-29"],"value":[4.97,5.01,5.0,4.99,5.05,"NA"]}],"limit":1000,"num_found":1,"offset":0}}"""
}
