import 'package:flutter/material.dart';

void main() => runApp(const WanXiangApp());

class WanXiangApp extends StatelessWidget {
  const WanXiangApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '{{appName}}',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int count = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('{{appName}}')),
      body: Center(
        child: Text('Count: $count', style: Theme.of(context).textTheme.headlineMedium),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => setState(() => count++),
        child: const Icon(Icons.add),
      ),
    );
  }
}
