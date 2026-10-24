package kr.seoulhousing.monitor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class MonitorRunner implements ApplicationRunner {
    private final HousingMonitorService monitor;
    private final ApplicationContext context;
    private final boolean run;
    public MonitorRunner(HousingMonitorService monitor, ApplicationContext context, @Value("${housing.monitor.run:false}") boolean run) { this.monitor = monitor; this.context = context; this.run = run; }
    @Override public void run(ApplicationArguments args) { if (run) { monitor.refresh(); System.exit(org.springframework.boot.SpringApplication.exit(context)); } }
}
